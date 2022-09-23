;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.platform.atomicreferencearray
  (:refer-clojure :exclude [set get]))

(set! *warn-on-reflection* true)

(defprotocol AtomicReferenceArray
  (set [a idx o])
  (get [a idx]))

(deftype AtomicReferenceArrayImpl [^java.util.concurrent.atomic.AtomicReferenceArray a]
  AtomicReferenceArray
  (set [_ idx o] (.set a idx o))
  (get [_ idx] (.get a idx)))

(defn atomic-reference-array [^long size] (->AtomicReferenceArrayImpl (java.util.concurrent.atomic.AtomicReferenceArray. size)))
