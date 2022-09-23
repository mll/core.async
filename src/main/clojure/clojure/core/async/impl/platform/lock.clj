;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.platform.lock)

(set! *warn-on-reflection* true)

(defprotocol Lock
  (lock [l])
  (unlock [l]))

(deftype LockImpl [^java.util.concurrent.locks.ReentrantLock lock]
  Lock
  (lock [_this] (.lock lock))
  (unlock [_this] (.unlock lock)))

(defn mutex [] (->LockImpl (java.util.concurrent.locks.ReentrantLock.)))




