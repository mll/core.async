
;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.platform.linkedlist
  (:refer-clojure :exclude [next remove]))

(set! *warn-on-reflection* true)

(defprotocol Iterator
  (next [l])
  (remove [l])
  (hasNext [l]))


(defprotocol LinkedList
  (isEmpty [l])
  (iterator [l])
  (clear [l])
  (size [l])
  (add [l o]))

(deftype IteratorImpl [^java.util.Iterator i]
  Iterator
  (next [_] (.next i))
  (remove [_] (.remove i))
  (hasNext [_] (.hasNext i)))

(deftype LinkedListImpl [^java.util.LinkedList l]
  LinkedList
  (isEmpty [_] (.isEmpty l))
  (iterator [_] (->IteratorImpl (.iterator l)))
  (clear [_] (.clear l))
  (size [_] (.size l))
  (add [_ o] (.add l o)))

(defn linked-list [] (->LinkedListImpl (java.util.LinkedList.)))
