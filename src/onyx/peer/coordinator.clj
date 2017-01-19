(ns onyx.peer.coordinator
  (:require [com.stuartsierra.component :as component]
            [onyx.schema :as os]
            [clojure.core.async :refer [>!! <!! poll! promise-chan sliding-buffer chan close! thread]]
            [onyx.static.planning :as planning]
            [taoensso.timbre :refer [debug info error warn trace fatal]]
            [schema.core :as s]
            [onyx.monitoring.measurements :refer [emit-latency emit-latency-value]]
            [com.stuartsierra.component :as component]
            [onyx.messaging.protocols.endpoint-status :as endpoint-status]
            [onyx.messaging.protocols.messenger :as m]
            [onyx.messaging.protocols.publisher :as pub]
            [onyx.messaging.messenger-state :as ms]
            [onyx.static.util :refer [ms->ns]]
            [onyx.peer.constants :refer [ALL_PEERS_SLOT]]
            [onyx.checkpoint :as checkpoint :refer [read-checkpoint-coordinate 
                                                    assume-checkpoint-coordinate
                                                    write-checkpoint-coordinate]]
            [onyx.extensions :as extensions]
            [onyx.log.replica]
            [onyx.static.default-vals :refer [arg-or-default]])
  (:import [org.apache.zookeeper KeeperException$BadVersionException]
           [java.util.concurrent.locks LockSupport]))

(defn input-publications [{:keys [peer-sites message-short-ids] :as replica} peer-id job-id]
  (let [allocations (get-in replica [:allocations job-id])
        input-tasks (get-in replica [:input-tasks job-id])
        coordinator-peer-id [:coordinator peer-id]]
    (->> input-tasks
         (mapcat (fn [task]
                   (->> (get allocations task)
                        (group-by (fn [input-peer]
                                    (get peer-sites input-peer)))
                        (map (fn [[site colocated-peers]]
                               {:src-peer-id coordinator-peer-id
                                :dst-task-id [job-id task]
                                :dst-peer-ids (set colocated-peers)
                                :short-id (get message-short-ids 
                                               {:src-peer-type :coordinator
                                                :src-peer-id peer-id
                                                :job-id job-id
                                                :dst-task-id task
                                                :msg-slot-id ALL_PEERS_SLOT})
                                :slot-id ALL_PEERS_SLOT
                                :site site})))))
         (set))))

(defn offer-heartbeats
  [{:keys [messenger] :as state}]
  (run! pub/offer-heartbeat! (m/publishers messenger))
  (assoc state :last-heartbeat-time (System/nanoTime)))

(defn offer-barriers [{:keys [messenger rem-barriers barrier-opts offering?] :as state}]
  (if offering? 
    (let [_ (run! pub/poll-heartbeats! (m/publishers messenger))
          offer-xf (comp (map (fn [pub]
                                [(m/offer-barrier messenger pub barrier-opts) 
                                 pub]))
                         (remove (comp pos? first))
                         (map second))
          new-remaining (sequence offer-xf rem-barriers)]
      (if (empty? new-remaining)
        (-> state 
            (assoc :checkpoint-version nil)
            (assoc :offering? false)
            (assoc :rem-barriers nil))   
        (do
         ;; sleep for two milliseconds before retrying the offer
         (LockSupport/parkNanos (* 2 1000000))
         (assoc state :rem-barriers new-remaining))))
    state))

(defn write-coordinate [curr-version log tenancy-id job-id coordinate]
  (try (->> curr-version
            (write-checkpoint-coordinate log tenancy-id job-id coordinate)
            (:version))
       (catch KeeperException$BadVersionException bve
         (info "Coordinator failed to write coordinates.
                This is likely due to the coordinator being quarantined, and another coordinator taking over.")
         curr-version)))

(defn complete-job! [state job-id]
  (>!! (:group-ch state) [:send-to-outbox {:fn :complete-job :args {:job-id job-id}}])
  state)

(defn next-replica 
  [{:keys [peer-config log job-id peer-id messenger curr-replica] :as state} 
   barrier-period-ns
   new-replica]
  (let [{:keys [onyx/tenancy-id]} peer-config
        curr-version (get-in curr-replica [:allocation-version job-id])
        new-version (get-in new-replica [:allocation-version job-id])
        reallocated? (not= curr-version new-version)]
    (cond reallocated?
          (let [new-messenger (-> messenger 
                                  (m/update-publishers (input-publications new-replica peer-id job-id))
                                  (m/set-replica-version! new-version)
                                  (m/set-epoch! 0))
                coordinates (read-checkpoint-coordinate log tenancy-id job-id)]
            (assoc state 
                   ;; should probably be 0 if we have non checkpoint barriers
                   :checkpoint-epoch 0
                   :sealing? false
                   :completed? false
                   :offering? true
                   :scheduled? false
                   :barrier-opts {:recover-coordinates coordinates}
                   :rem-barriers (m/publishers new-messenger)
                   :curr-replica new-replica
                   :messenger new-messenger))

          :else
          (assoc state :curr-replica new-replica))))

(defn complete-job 
  [{:keys [peer-config log job-id messenger write-version] :as state}]
  (let [{:keys [onyx/tenancy-id]} peer-config
        replica-version (m/replica-version messenger)
        epoch (m/epoch messenger)]
    (let [coordinates {:tenancy-id tenancy-id :job-id job-id :replica-version replica-version :epoch epoch} 
          next-write-version (write-coordinate write-version log tenancy-id job-id coordinates)]
      (-> state
          (complete-job! job-id)
          (assoc :completed? true)
          (assoc :sealing? false)
          (assoc :write-version next-write-version)))))

;; NEXT SEND BACK INPUT STATUSES. THEN WE CAN JUST WAIT TO SEE
(defn merge-statuses 
  "Combines many statuses into one overall status that conveys the
   minimum/worst case of all of the statuses" 
  [[fst & rst]]
  (reduce (fn [c s]
            {:ready? (and (:ready? s) (:ready? c))
             :drained? (and (:drained? s) (:drained? c))
             :replica-version (if-let [rvs (seq (keep :replica-version [c s]))]
                                (apply min rvs)                   
                                -1)
             :checkpointing? (or (:checkpointing? s) (:checkpointing? c))
             :heartbeat (min (:heartbeat c) (:heartbeat s))
             :epoch (min (:epoch c) (:epoch s))
             :min-epoch (min (:min-epoch c) (:min-epoch s))})
          fst
          rst))

(defn merged-statuses [messenger]
  (->> (m/publishers messenger)
       (mapcat (comp endpoint-status/statuses pub/endpoint-status))
       (map val)
       (merge-statuses)))

(defn periodic-barrier 
  [{:keys [peer-config write-version workflow-depth log 
           curr-replica job-id messenger offering?] :as state}]
  (if offering?
    ;; No op because hasn't finished emitting last barrier, wait again
    state
    (let [{:keys [onyx/tenancy-id]} peer-config
          job-sealed? (boolean (get-in curr-replica [:completed-job-coordinates job-id]))
          checkpointed-epoch (:min-epoch (merged-statuses messenger))
          write-coordinate? (> checkpointed-epoch 0)
          coordinates {:tenancy-id tenancy-id
                       :job-id job-id
                       :replica-version (m/replica-version messenger) 
                       :epoch checkpointed-epoch}
          ;; get the next version of the zk node, so we can detect when there are other writers
          next-write-version (if write-coordinate?
                               (write-coordinate write-version log tenancy-id job-id coordinates)
                               write-version)
          checkpoint? (first (shuffle [true #_false]))
          messenger (m/set-epoch! messenger (inc (m/epoch messenger)))]
      (assoc state 
             :checkpointing? checkpoint?
             :checkpoint-epoch (if checkpoint? (m/epoch messenger) (:checkpoint-epoch state))
             :offering? true
             :write-version next-write-version
             :barrier-opts {:completed? (:sealing? state)
                            :checkpoint? checkpoint?}
             :rem-barriers (m/publishers messenger)
             :messenger messenger))))

(defn shutdown [{:keys [peer-config log workflow-depth job-id messenger] :as state}]
  (assoc state :messenger (component/stop messenger)))

(defn initialise-state [{:keys [log job-id peer-config] :as state}]
  (let [{:keys [onyx/tenancy-id]} peer-config
        write-version (assume-checkpoint-coordinate log tenancy-id job-id)] 
    (-> state 
        (assoc :write-version write-version)
        (assoc :last-heartbeat-time (System/nanoTime)))))

(defn checkpointing? [state status]
  (and ;; we've initiated a snapshot
       (:checkpointing? state)
       ;; none of the statuses are saying they're checkpointing
       (or (:checkpointing? status)
           ;; and they are all at least up to the checkpoint barrier
           ;; which means that we are not getting a stale status
           (< (:min-epoch status)
               (:checkpoint-epoch state)))))

(defn coordinator-iteration 
  [{:keys [messenger last-heartbeat-time next-barrier-time offering? completed? sealing? scheduled?
           allocation-ch shutdown-ch peer-config] :as state}
   coordinator-max-sleep-ns
   barrier-period-ns
   heartbeat-ns]
  (let [_ (run! pub/poll-heartbeats! (m/publishers messenger))
        status (merged-statuses messenger)] 
    ;(println "COORDINATOR STATUS" status (m/replica-version messenger) (m/epoch messenger) :sealing? (:sealing? state))
    (cond (> (System/nanoTime) (+ last-heartbeat-time heartbeat-ns))
          ;; Immediately offer heartbeats
          (offer-heartbeats state)

          offering?
          ;; Continue offering barriers until success
          (offer-barriers state) 

          completed?
          (do
           (LockSupport/parkNanos coordinator-max-sleep-ns)
           state)

          sealing?
          (if (checkpointing? state status)
            (do
             (LockSupport/parkNanos coordinator-max-sleep-ns)
             state) 
            (complete-job state))

          (or (not= (m/replica-version messenger) (:replica-version status))
              (not= (m/epoch messenger) (:min-epoch status)))
          (do
           (LockSupport/parkNanos coordinator-max-sleep-ns)
           state)

          (:drained? status)
          ;; emit final completion barrier
          (periodic-barrier (assoc state :sealing? true :scheduled? false))

          (and scheduled? (> (System/nanoTime) next-barrier-time))
          (periodic-barrier (assoc state :sealing? false :scheduled? false))  

          (and (not scheduled?)
               (not (checkpointing? state status)))
          (assoc state 
                 :next-barrier-time (+ (System/nanoTime) barrier-period-ns) 
                 :scheduled? true
                 :checkpointing? false)

          :else
          (do
           (LockSupport/parkNanos coordinator-max-sleep-ns)
           state))))

(defn start-coordinator! 
  [{:keys [allocation-ch shutdown-ch peer-config] :as state}]
  (thread
   (try
    (let [;; FIXME: allow in job data
          ;snapshot-every-n (arg-or-default :onyx.peer/coordinator-snapshot-every-n-barriers peer-config)
          coordinator-max-sleep-ns (ms->ns (arg-or-default :onyx.peer/coordinator-max-sleep-ms peer-config))
          barrier-period-ns (ms->ns (arg-or-default :onyx.peer/coordinator-barrier-period-ms peer-config))
          heartbeat-ns (ms->ns (arg-or-default :onyx.peer/heartbeat-ms peer-config))
          result (loop [{:keys [messenger] :as state} (initialise-state state)]
                   (if-let [scheduler-event (poll! shutdown-ch)]
                     (do (shutdown (assoc state :scheduler-event scheduler-event))
                         :shutdown)
                     (if-let [new-replica (poll! allocation-ch)]
                       ;; Set up reallocation barriers. Will be sent on next recur through :offer-barriers
                       (recur (next-replica state barrier-period-ns new-replica))
                       (recur (coordinator-iteration state
                                                     coordinator-max-sleep-ns
                                                     barrier-period-ns
                                                     heartbeat-ns)))))] 
        (when-not (= result :shutdown)
          (throw (Exception. "Coordinator not properly shutdown"))))
    (catch Throwable e
      (>!! (:group-ch state) [:restart-vpeer (:peer-id state)])
      (fatal e "Error in coordinator")))))

(defprotocol Coordinator
  (start [this])
  (stop [this scheduler-event])
  (started? [this])
  (next-state [this old-replica new-replica]))

(defn emit-replica [{:keys [allocation-ch] :as coordinator} replica]
  (when (started? coordinator) 
    (>!! allocation-ch replica))
  coordinator)

(defn start-messenger [messenger replica job-id]
  (-> (component/start messenger) 
      (m/set-replica-version! (get-in replica [:allocation-version job-id] -1))))

(defn stop-coordinator! [{:keys [shutdown-ch allocation-ch peer-id]} scheduler-event]
  (when shutdown-ch
    (info "Stopping coordinator on:" peer-id)
    (>!! shutdown-ch scheduler-event)
    (close! shutdown-ch))
  (when allocation-ch 
    (close! allocation-ch))
  (info "Coordinator stopped."))

(defrecord PeerCoordinator 
  [workflow resume-point log messenger-group peer-config peer-id job-id
   messenger group-ch allocation-ch shutdown-ch coordinator-thread]
  Coordinator
  (start [this] 
    (info "Piggybacking coordinator on peer:" peer-id)
    (let [initial-replica (onyx.log.replica/starting-replica peer-config)
          messenger (-> (m/build-messenger peer-config messenger-group [:coordinator peer-id])
                        (start-messenger initial-replica job-id)) 
          allocation-ch (chan (sliding-buffer 1))
          shutdown-ch (promise-chan)
          workflow-depth (planning/workflow-depth workflow)]
      (assoc this 
             :started? true
             :allocation-ch allocation-ch
             :shutdown-ch shutdown-ch
             :messenger messenger
             :coordinator-thread (start-coordinator! 
                                   {:workflow-depth workflow-depth
                                    :resume-point resume-point
                                    :log log
                                    :peer-config peer-config 
                                    :messenger messenger 
                                    :curr-replica initial-replica 
                                    :job-id job-id
                                    :peer-id peer-id 
                                    :group-ch group-ch
                                    :allocation-ch allocation-ch 
                                    :shutdown-ch shutdown-ch}))))
  (started? [this]
    (true? (:started? this)))
  (stop [this scheduler-event]
    (stop-coordinator! this scheduler-event)
    (assoc this :allocation-ch nil :started? false :shutdown-ch nil :coordinator-thread nil))
  (next-state [this old-replica new-replica]
    (let [started? (= (get-in old-replica [:coordinators job-id]) peer-id)
          start? (= (get-in new-replica [:coordinators job-id]) peer-id)]
      (cond-> this
        (and (not started?) start?)
        (start)

        (and started? (not start?))
        (stop :rescheduled)

        :else
        (emit-replica new-replica)))))

(defn new-peer-coordinator 
  [workflow resume-point log messenger-group peer-config peer-id job-id group-ch]
  (map->PeerCoordinator {:workflow workflow
                         :resume-point resume-point
                         :log log
                         :group-ch group-ch
                         :messenger-group messenger-group 
                         :peer-config peer-config 
                         :peer-id peer-id 
                         :job-id job-id}))
