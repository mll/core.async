;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.platform.core
  (:require [clojure.core.async.impl.protocols :as impl])
  (:import [java.util Arrays]))

(set! *warn-on-reflection* true)

(defn copy-of [^objects array ^Integer count]
  (Arrays/copyOf array count))

(defn array-copy [^objects a1 ^objects a2 ^long idx3]
  (System/arraycopy a1 0 a2 0 idx3))

(defn default-exception-handler []
  (fn [ex]
    (-> (Thread/currentThread)
        .getUncaughtExceptionHandler
        (.uncaughtException (Thread/currentThread) ex))
    nil))







