(ns ^:no-doc onyx.peer.function
  (:require [clojure.core.async :refer [chan >! go alts!! close! timeout]]
            [onyx.static.planning :refer [find-task]]
            [onyx.messaging.acking-daemon :as acker]
            [onyx.peer.pipeline-extensions :as p-ext]
            [onyx.peer.operation :as operation]
            [onyx.extensions :as extensions]
            [taoensso.timbre :as timbre :refer [debug info]]
            [onyx.types :refer [->Leaf]])
  (:import [java.util UUID]))

(defn pick-peer [id active-peers hash-group max-downstream-links]
  (when-not (empty? active-peers)
    (if hash-group
      (nth active-peers
           (mod hash-group
                (count active-peers)))
      (rand-nth (operation/select-n-peers id active-peers max-downstream-links)))))

(defn read-batch 
  ([event]
   (read-batch event (:onyx.core/messenger event)))
  ([event messenger]
   {:onyx.core/batch (onyx.extensions/receive-messages messenger event)}))

(defn write-batch
  ([event replica peer-replica-view state id messenger job-id max-downstream-links egress-tasks]
   (let [results (:segments (:onyx.core/results event))]
     (when-not (empty? results)
       (let [replica-val @replica
             peer-replica-val @peer-replica-view
             active-peers (get (:active-peers peer-replica-val) job-id)]
         (doseq [[route m] results]
           (let [task-peers (get active-peers (get egress-tasks route))] 
             (doseq [[hash-group segs] m]
               (let [target (pick-peer id task-peers hash-group max-downstream-links)]
                 (when target
                   (let [link (operation/peer-link replica-val state event target)]
                     (onyx.extensions/send-messages messenger event link segs)))))))
         {}))))

  ([{:keys [onyx.core/id onyx.core/results 
            onyx.core/messenger onyx.core/job-id 
            onyx.core/state onyx.core/replica 
            onyx.core/peer-replica-view onyx.core/serialized-task 
            onyx.core/max-downstream-links] :as event}]
   (write-batch event replica peer-replica-view state id messenger job-id max-downstream-links (:egress-ids serialized-task))))

(defrecord Function [replica peer-replica-view state id messenger job-id max-downstream-links egress-tasks]
  p-ext/Pipeline
  (read-batch 
    [_ event]
    (read-batch event messenger))

  (write-batch 
    [_ event]
    (write-batch event replica peer-replica-view state id messenger job-id max-downstream-links egress-tasks))

  (seal-resource [_ _]
    nil))

(defn function [{:keys [onyx.core/replica
                        onyx.core/peer-replica-view
                        onyx.core/state
                        onyx.core/id 
                        onyx.core/messenger 
                        onyx.core/job-id 
                        onyx.core/max-downstream-links
                        onyx.core/serialized-task] :as pipeline-data}]
  (->Function replica
              peer-replica-view
              state
              id
              messenger
              job-id
              max-downstream-links
              (:egress-ids serialized-task)))
