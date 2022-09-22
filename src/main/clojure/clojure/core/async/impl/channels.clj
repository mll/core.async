;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.channels
  (:require [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.platform :as platform])
  (:import [java.util LinkedList Queue]
           [clojure.core.async.impl.protocols Lock Buffer]
           [clojure.lang IDeref]))

(set! *warn-on-reflection* true)

(defmacro assert-unlock [lock test msg]
  `(when-not ~test
     (impl/unlock ~lock)
     (throw (new AssertionError (str "Assert failed: " ~msg "\n" (pr-str '~test))))))

(defn box [val]
  (reify IDeref
         (deref [_] val)))

(defprotocol MMC
  (cleanup [_])
  (abort [_]))

(deftype ManyToManyChannel [^LinkedList takes ^LinkedList puts ^Queue buf closed ^Lock mutex add!]
  MMC
  (cleanup
   [_]
   (when-not (.isEmpty takes)
     (let [iter (.iterator takes)]
       (loop [taker (.next iter)]
         (when-not (impl/active? taker)
           (.remove iter))
         (when (.hasNext iter)
           (recur (.next iter))))))
   (when-not (.isEmpty puts)
     (let [iter (.iterator puts)]
       (loop [[putter] (.next iter)]
         (when-not (impl/active? putter)
           (.remove iter))
         (when (.hasNext iter)
           (recur (.next iter)))))))

  (abort
   [this]
   (let [iter (.iterator puts)]
     (when (.hasNext iter)
       (loop [[^impl/Lock putter] (.next iter)]
         (impl/lock putter)
         (let [put-cb (and (impl/active? putter) (impl/commit putter))]
           (impl/unlock putter)
           (when put-cb
             (platform/run (fn [] (put-cb true))))
           (when (.hasNext iter)
             (recur (.next iter)))))))
   (.clear puts)
   (impl/close! this))

  impl/WritePort
  (put!
   [this val handler]
   (when (nil? val)
     (throw (IllegalArgumentException. "Can't put nil on channel")))
   (impl/lock mutex)
   (cleanup this)
   (if @closed
     (let [^impl/Lock handler handler]
       (impl/lock handler)
       (when (impl/active? handler) (impl/commit handler))
       (impl/unlock handler)
       (impl/unlock mutex)
       (box false))
     (let [^impl/Lock handler handler]
       (if (and buf (not (impl/full? buf)) (not (.isEmpty takes)))
         (do
           (impl/lock handler)
           (let [put-cb (and (impl/active? handler) (impl/commit handler))]
             (impl/unlock handler)
             (if put-cb
               (let [done? (reduced? (add! buf val))]
                 (if (pos? (count buf))
                   (let [iter (.iterator takes)
                         take-cbs (loop [takers []]
                                    (if (and (.hasNext iter) (pos? (count buf)))
                                      (let [^impl/Lock taker (.next iter)]
                                        (impl/lock taker)
                                        (let [ret (and (impl/active? taker) (impl/commit taker))]
                                          (impl/unlock taker)
                                          (if ret
                                            (let [val (impl/remove! buf)]
                                              (.remove iter)
                                              (recur (conj takers (fn [] (ret val)))))
                                            (recur takers))))
                                      takers))]
                     (if (seq take-cbs)
                       (do
                         (when done?
                           (abort this))
                         (impl/unlock mutex)
                         (doseq [f take-cbs]
                           (platform/run f)))
                       (do
                         (when done?
                           (abort this))
                         (impl/unlock mutex))))
                   (do
                     (when done?
                       (abort this))
                     (impl/unlock mutex)))
                 (box true))
               (do (impl/unlock mutex)
                   nil))))
         (let [iter (.iterator takes)
               [put-cb take-cb] (when (.hasNext iter)
                                  (loop [^impl/Lock taker (.next iter)]
                                    (if (< (impl/lock-id handler) (impl/lock-id taker))
                                      (do (impl/lock handler) (impl/lock taker))
                                      (do (impl/lock taker) (impl/lock handler)))
                                    (let [ret (when (and (impl/active? handler) (impl/active? taker))
                                                [(impl/commit handler) (impl/commit taker)])]
                                      (impl/unlock handler)
                                      (impl/unlock taker)
                                      (if ret
                                        (do
                                          (.remove iter)
                                          ret)
                                        (when (.hasNext iter)
                                          (recur (.next iter)))))))]
           (if (and put-cb take-cb)
             (do
               (impl/unlock mutex)
               (platform/run (fn [] (take-cb val)))
               (box true))
             (if (and buf (not (impl/full? buf)))
               (do
                 (impl/lock handler)
                 (let [put-cb (and (impl/active? handler) (impl/commit handler))]
                   (impl/unlock handler)
                   (if put-cb
                     (let [done? (reduced? (add! buf val))]
                       (when done?
                         (abort this))
                       (impl/unlock mutex)
                       (box true))
                     (do (impl/unlock mutex)
                         nil))))
               (do
                 (when (and (impl/active? handler) (impl/blockable? handler))
                   (assert-unlock mutex
                                  (< (.size puts) impl/MAX-QUEUE-SIZE)
                                  (str "No more than " impl/MAX-QUEUE-SIZE
                                       " pending puts are allowed on a single channel."
                                       " Consider using a windowed buffer."))
                   (.add puts [handler val]))
                 (impl/unlock mutex)
                 nil))))))))

  impl/ReadPort
  (take!
   [this handler]
   (impl/lock mutex)
   (cleanup this)
   (let [^impl/Lock handler handler
         commit-handler (fn []
                          (impl/lock handler)
                          (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                            (impl/unlock handler)
                            take-cb))]
     (if (and buf (pos? (count buf)))
       (if-let [take-cb (commit-handler)]
         (let [val (impl/remove! buf)
               iter (.iterator puts)
               [done? cbs]
               (when (and (not (impl/full? buf)) (.hasNext iter))
                 (loop [cbs []
                        [^impl/Lock putter val] (.next iter)]
                   (impl/lock putter)
                   (let [cb (and (impl/active? putter) (impl/commit putter))]
                     (impl/unlock putter)
                     (.remove iter)
                     (let [cbs (if cb (conj cbs cb) cbs)
                           done? (when cb (reduced? (add! buf val)))]
                       (if (and (not done?) (not (impl/full? buf)) (.hasNext iter))
                         (recur cbs (.next iter))
                         [done? cbs])))))]
           (when done?
             (abort this))
           (impl/unlock mutex)
           (doseq [cb cbs]
             (platform/run #(cb true)))
           (box val))
         (do (impl/unlock mutex)
             nil))
       (let [iter (.iterator puts)
             [take-cb put-cb val]
             (when (.hasNext iter)
               (loop [[^impl/Lock putter val] (.next iter)]
                 (if (< (impl/lock-id handler) (impl/lock-id putter))
                   (do (impl/lock handler) (impl/lock putter))
                   (do (impl/lock putter) (impl/lock handler)))
                 (let [ret (when (and (impl/active? handler) (impl/active? putter))
                             [(impl/commit handler) (impl/commit putter) val])]
                   (impl/unlock handler)
                   (impl/unlock putter)
                   (if ret
                     (do
                       (.remove iter)
                       ret)
                     (when-not (impl/active? putter)
                       (.remove iter)
                       (when (.hasNext iter)
                         (recur (.next iter))))))))]
         (if (and put-cb take-cb)
           (do
             (impl/unlock mutex)
             (platform/run #(put-cb true))
             (box val))
           (if @closed
             (do
               (when buf (add! buf))
               (let [has-val (and buf (pos? (count buf)))]
                 (if-let [take-cb (commit-handler)]
                   (let [val (when has-val (impl/remove! buf))]
                     (impl/unlock mutex)
                     (box val))
                   (do
                     (impl/unlock mutex)
                     nil))))
             (do
               (when (impl/blockable? handler)
                 (assert-unlock mutex
                                (< (.size takes) impl/MAX-QUEUE-SIZE)
                                (str "No more than " impl/MAX-QUEUE-SIZE
                                     " pending takes are allowed on a single channel."))
                 (.add takes handler))
               (impl/unlock mutex)
               nil)))))))

  impl/Channel
  (closed? [_] @closed)
  (close!
   [this]
   (impl/lock mutex)
   (cleanup this)
   (if @closed
     (do
       (impl/unlock mutex)
       nil)
     (do
       (reset! closed true)
       (when (and buf (.isEmpty puts))
         (add! buf))
       (let [iter (.iterator takes)]
         (when (.hasNext iter)
           (loop [^impl/Lock taker (.next iter)]
             (impl/lock taker)
             (let [take-cb (and (impl/active? taker) (impl/commit taker))]
               (impl/unlock taker)
               (when take-cb
                 (let [val (when (and buf (pos? (count buf))) (impl/remove! buf))]
                   (platform/run (fn [] (take-cb val)))))
               (.remove iter)
               (when (.hasNext iter)
                 (recur (.next iter)))))))
       (when buf (impl/close-buf! buf))
       (impl/unlock mutex)
       nil))))

(defn- ex-handler [ex]
  (-> (Thread/currentThread)
      .getUncaughtExceptionHandler
      (.uncaughtException (Thread/currentThread) ex))
  nil)

(defn- handle [buf exh t]
  (let [else ((or exh ex-handler) t)]
    (if (nil? else)
      buf
      (impl/add! buf else))))

(defn chan
  ([buf] (chan buf nil))
  ([buf xform] (chan buf xform nil))
  ([buf xform exh]
     (ManyToManyChannel.
      (LinkedList.) (LinkedList.) buf (atom false) (platform/mutex)
      (let [add! (if xform (xform impl/add!) impl/add!)]
        (fn
          ([buf]
             (try
               (add! buf)
               (catch Throwable t
                 (handle buf exh t))))
          ([buf val]
             (try
               (add! buf val)
               (catch Throwable t
                 (handle buf exh t)))))))))
