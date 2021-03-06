;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns gloss.test.core
  (:use
    [gloss core io]
    [gloss.core.formats :only (to-char-buffer)]
    [gloss.core.protocols :only (write-bytes read-bytes)]
    [gloss.data.bytes :only (take-bytes drop-bytes dup-bytes take-contiguous-bytes)]
    [lamina core]
    [clojure test walk]))

(defn convert-char-sequences [x]
  (postwalk
    #(if (instance? CharSequence %)
       (str %)
       %)
    x))

(defn convert-buf-seqs [x]
  (postwalk
    #(if (and (sequential? %) (instance? java.nio.ByteBuffer (first %)))
       [(contiguous %)]
       %)
    x))

(defn convert-string-sequence [x]
  (if (and (sequential? x) (every? string? x))
    (apply str x)
    x))

(defn convert-result [x]
  (-> x convert-buf-seqs convert-char-sequences convert-string-sequence))

(defn is= [a b]
  (is
    (= (convert-string-sequence a) (convert-result b))
    (str (prn-str a) (prn-str b))))

(defn partition-bytes [interval bytes]
  (let [buf-seq (to-buf-seq bytes)]
    (apply concat
      (map
	#(take-bytes 1 (drop-bytes % buf-seq))
	(range (byte-count buf-seq))))))

(defn split-bytes [index bytes]
  (let [bytes (dup-bytes bytes)]
    [(take-bytes index bytes) (drop-bytes index bytes)]))

(defn test-stream-roundtrip [split-fn frame val]
  (let [bytes (split-fn (encode-all frame [val val]))
	in (channel)
	out (decode-channel frame in)]
    (doseq [b bytes]
      (enqueue in b))
    (let [s (convert-result (channel-seq out))]
      (is= [val val] s))))

(defn test-roundtrip [f val]
  (let [f (compile-frame f)
	bytes (encode f val)
	val (convert-char-sequences (decode f bytes))
	bytes (encode-all f [val val])
	result (decode-all f bytes)
	split-result (decode-all f (partition-bytes 1 (dup-bytes bytes)))]
    (test-stream-roundtrip #(partition-bytes 1 %) f val)
    (is= [val val] result)
    (is= [val val] split-result)
    (doseq [i (range 1 (byte-count bytes))]
      (is= [val val] (decode-all f (apply concat (split-bytes i bytes))))
      (test-stream-roundtrip #(split-bytes i %) f val))))

(defn test-full-roundtrip [f buf val]
  (is= val (decode f (dup-bytes buf)))
  (is (= buf (write-bytes f nil val))))

(deftest test-lists
  (test-roundtrip
    [:float32 :float32]
    [1 2])
  (test-roundtrip
    [:a :byte :float64 :b]
    [:a 1 2 :b])
  (test-roundtrip
    [:int16 [:int32 [:int64]]]
    [1 [2 [3]]]))

(deftest test-maps
  (test-roundtrip
    {:a :int32 :b :int32}
    {:a 1 :b 2})
  (test-roundtrip
    {:a :int32 :b [:int32 :int32]}
    {:a 1 :b [2 3]})
  (test-roundtrip
    {:a :int32 :b {:c {:d :int16}}}
    {:a 1 :b {:c {:d 2}}})
  (test-roundtrip
    [{:a :int32} {:b [:float64 :float32]}]
    [{:a 1} {:b [2 3]}]))

(deftest test-repeated
  (test-roundtrip
    (repeated :int32)
    (range 10))
  (test-roundtrip
    (repeated [:byte :byte])
    (partition 2 (range 100)))
  (test-roundtrip
    (repeated :byte :delimiters [64])
    (range 10))
  (test-roundtrip
    (repeated (string :utf-8 :delimiters ["/n"]) :delimiters ["/0"])
    ["foo" "bar" "baz"])
  (test-roundtrip
    (repeated {:a :int32 :b :int32})
    (repeat 10 {:a 1 :b 2}))
  (test-roundtrip
    (repeated :int32 :prefix (prefix :byte))
    (range 10))
  (test-roundtrip
    (repeated :byte :prefix :int32)
    (range 10))
  (test-roundtrip
    (finite-frame (prefix :int16)
      (repeated :int32 :prefix :none))
    (range 10))
  (test-roundtrip
    [:byte (repeated :int32)]
    [1 [2]]))

(deftest test-finite-block
  (test-roundtrip
    [:byte :int16
     (finite-block
       (prefix :int64
	 #(- % 4)
	 #(+ % 4)))]
    [1 1 (encode (repeated :int16) (range 5))]))

(deftest test-complex-prefix
  (let [p (prefix [:byte :byte]
	    second
	    (fn [x] [\$ x]))
	codec (repeated :byte :prefix p)
	buf (to-byte-buffer [\$ 3 1 2 3])]
    (test-full-roundtrip codec [buf] [1 2 3])))

(deftest test-simple-header
  (let [b->h (fn [body]
	       (get
		 {:a 1 :b 2 :c 3}
		 (first body)))
	h->b (fn [hd]
	       (condp = hd
		 1 (compile-frame [:a :int16])
		 2 (compile-frame [:b :float32])
		 3 (compile-frame [:c (string :utf-8 :delimiters [\0])])))
	codec (header :byte h->b b->h)]
    (test-roundtrip codec [:a 1])
    (test-roundtrip codec [:b 2.5])
    (test-roundtrip codec [:c "abc"])))

(deftest test-enum
  (test-roundtrip
    (enum :byte :a :b :c)
    :a)
  (test-roundtrip
    (enum :int16 {:a 100 :b 1000})
    :b))

(deftest test-string
  (test-roundtrip
    (string :utf-8)
    "abcd")
  (test-roundtrip
    (repeated (string :utf-8 :delimiters ["\0"]))
    ["abc" "def"])
  (test-roundtrip
    [:a (string :utf-8 :delimiters ["xyz"])]
    [:a "abc"])
  (test-roundtrip
    (finite-frame 5 (string :utf-8))
    "abcde"))

(deftest test-string-numbers
  (test-roundtrip
    (repeated (string-integer :utf-8 :length 5))
    [12345 67890])
  (test-roundtrip
    (repeated (string-integer :ascii :delimiters ["x"]))
    [1 23 456 7890])
  (test-roundtrip
    (repeated (string-float :ascii :delimiters ["x"]))
    [(/ 3 2) 1.5 0.66666])
  (test-roundtrip
    (repeated :int32
      :prefix (prefix (string-integer :ascii :delimiters ["x"])))
    [1 2 3]))

(deftest test-ordered-map
  (test-roundtrip
    (ordered-map :b :int32 :a :int32)
    {:a 1 :b 2})
  (test-roundtrip
    (ordered-map :b :int32 :a [:int32 :int32])
    {:a [2 3] :b 1}))
