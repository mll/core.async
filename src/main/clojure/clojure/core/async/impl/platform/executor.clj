;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.platform.executor
  (:import [java.util.concurrent Executors ThreadLocalRandom ThreadFactory]))

(set! *warn-on-reflection* true)

(defprotocol Executor
  (execute [executor runnable]))

(defonce ^:private 
  ^{:doc "Number of processors reported by the JVM"}
  processors (.availableProcessors (Runtime/getRuntime)))

(defonce ^:private 
  ^{:doc "Value is set via clojure.core.async.pool-size system property; defaults to 8; uses a
   delay so property can be set from code after core.async namespace is loaded but before
   any use of the async thread pool."}
  pool-size
  (delay (or (Long/getLong "clojure.core.async.pool-size") 8)))

(defonce ^:private in-dispatch (ThreadLocal.))

(deftype ExecutorImpl [^java.util.concurrent.Executor executor]
  Executor
  (execute [_this function]
    (.execute executor function)))

(defn counted-thread-factory
  "Create a ThreadFactory that maintains a counter for naming Threads.
     name-format specifies thread names - use %d to include counter
     daemon is a flag for whether threads are daemons or not
     opts is an options map:
       init-fn - function to run when thread is created"
  ([name-format daemon]
    (counted-thread-factory name-format daemon nil))
  ([name-format daemon {:keys [init-fn] :as opts}]
   (let [counter (atom 0)]
     (reify
       ThreadFactory
       (newThread [_this runnable]
         (let [body (if init-fn
                      (fn [] (init-fn) (.run ^Runnable runnable))
                      runnable)
               t (Thread. ^Runnable body)]
           (doto t
             (.setName (format name-format (swap! counter inc)))
             (.setDaemon daemon))))))))

(defn thread-macro-executor [] (->ExecutorImpl (Executors/newCachedThreadPool (counted-thread-factory "async-thread-macro-%d" true))))

(defn thread-pool-executor
  ([]
    (thread-pool-executor nil))
  ([init-fn]
   (let [executor-svc (Executors/newFixedThreadPool
                        @pool-size
                        (counted-thread-factory "async-dispatch-%d" true
                          {:init-fn init-fn}))]
     (reify Executor
       (execute [_ r]
         (.execute executor-svc ^Runnable r))))))

(defonce executor
  (delay (thread-pool-executor #(.set ^ThreadLocal in-dispatch true))))

(defn in-dispatch-thread?
  "Returns true if the current thread is a go block dispatch pool thread"
  []
  (boolean (.get ^ThreadLocal in-dispatch)))

(defn check-blocking-in-dispatch
  "If the current thread is a dispatch pool thread, throw an exception"
  []
  (when (.get ^ThreadLocal in-dispatch)
    (throw (IllegalStateException. "Invalid blocking call in dispatch thread"))))

(defn run
  "Runs Runnable r in a thread pool thread"
  [^Runnable r]
  (execute @executor r))
