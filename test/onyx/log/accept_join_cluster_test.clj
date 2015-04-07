(ns onyx.log.accept-join-cluster-test
  (:require [onyx.extensions :as extensions]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.system]
            [midje.sweet :refer :all]))

(let [entry (create-log-entry :accept-join-cluster
                              {:observer :d
                               :subject :b
                               :accepted-joiner :d
                               :accepted-observer :a})
      f (partial extensions/apply-log-entry entry)
      rep-diff (partial extensions/replica-diff entry)
      rep-reactions (partial extensions/reactions entry)
      old-replica {:pairs {:a :b :b :c :c :a}
                   :accepted {:a :d}
                   :peers [:a :b :c]
                   :job-scheduler :onyx.job-scheduler/greedy}
      new-replica (f old-replica)
      diff (rep-diff old-replica new-replica)]
  (fact (get-in new-replica [:pairs :a]) => :d)
  (fact (get-in new-replica [:pairs :d]) => :b)
  (fact (get-in new-replica [:accepted]) => {})
  (fact (last (get-in new-replica [:peers])) => :d)
  (fact diff => {:observer :a :subject :d})
  (fact (rep-reactions old-replica new-replica diff {}) => []))

(let [entry (create-log-entry :accept-join-cluster
                              {:observer :d
                               :subject :b
                               :accepted-joiner :d
                               :accepted-observer :a})
      f (partial extensions/apply-log-entry entry)
      rep-diff (partial extensions/replica-diff entry)
      rep-reactions (partial extensions/reactions entry)
      old-replica {:pairs {} 
                   :accepted {:a :d} 
                   :peers [:a]
                   :job-scheduler :onyx.job-scheduler/greedy}
      new-replica (f old-replica)
      diff (rep-diff old-replica new-replica)]
  (fact (get-in new-replica [:pairs :d]) => :a)
  (fact (get-in new-replica [:pairs :a]) => :d)
  (fact (get-in new-replica [:accepted]) => {})
  (fact (last (get-in new-replica [:peers])) => :d)
  (fact diff => {:observer :a :subject :d})
  (fact (rep-reactions old-replica new-replica diff {}) => []))

