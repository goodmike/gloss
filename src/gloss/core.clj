;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns gloss.core
  (:use
    potemkin
    [gloss.core protocols]
    [gloss.data primitives])
  (:require
    [gloss.data.bytes :as bytes]
    [gloss.core.formats :as formats]
    [gloss.data.string :as string]
    [gloss.core.codecs :as codecs]
    [gloss.core.structure :as structure]))

;;;

(import-fn #'structure/compile-frame)

(defmacro defcodec
  "Defines a compiled frame."
  [name frame]
  `(def ~name (compile-frame ~frame)))

;;;

(import-fn #'formats/to-byte-buffer)
(import-fn #'formats/to-buf-seq)

(defn contiguous
  "Takes a sequence of ByteBuffers and returns a single contiguous ByteBuffer."
  [buf-seq]
  (when buf-seq
    (bytes/take-contiguous-bytes (bytes/byte-count buf-seq) buf-seq)))

(defn encode
  "Turns a frame value into a sequence of ByteBuffers."
  [codec val]
  (when val
    (write-bytes codec nil val)))

(defn encode-all
  "Turns a sequence of frame values into a sequence of ByteBuffers."
  [codec vals]
  (apply concat
    (map #(write-bytes codec nil %) vals)))

(defn decode
  "Turns bytes into a single frame value.  If there are too few or too many bytes
   for the frame, an exception is thrown."
  [codec bytes]
  (let [buf-seq (bytes/dup-bytes (to-buf-seq bytes))
	[success val remainder] (read-bytes codec buf-seq)]
    (when-not success
      (throw (Exception. "Insufficient bytes to decode frame.")))
    (when-not (empty? remainder)
      (throw (Exception. "Bytes left over after decoding frame.")))
    val))

(defn decode-all
  "Turns bytes into a sequence of frame values.  If there are bytes left over at the end
   of the sequence, an exception is thrown."
  [codec bytes]
  (let [buf-seq (bytes/dup-bytes (to-buf-seq bytes))]
    (loop [buf-seq buf-seq, vals []]
      (if (empty? buf-seq)
	vals
	(let [[success val remainder] (read-bytes codec buf-seq)]
	  (when-not success
	    (throw (Exception. "Bytes left over after decoding sequence of frames.")))
	  (recur remainder (conj vals val)))))))

;;;

(import-fn codecs/enum)

(defn delimited-block
  "Defines a frame which is just a byte sequence terminated by one or more delimiters.

   If strip-delimiters? is true, the resulting byte sequences will not contain the
   delimiters."
  [delimiters strip-delimiters?]
  (bytes/delimited-bytes-codec
    (map to-byte-buffer delimiters)
    strip-delimiters?))

(defn finite-block
  "Defines a frame which is just a fixed-length byte seuqences."
  [len]
  (bytes/finite-byte-codec len))

(defn delimited-frame
  "Defines a frame which is terminated by delimiters."
  [delimiters frame]
  (bytes/delimited-codec
    (map to-byte-buffer delimiters)
    (compile-frame frame)))

(defn finite-frame
  "Defines a frame which is either of finite length, or has a prefix
   which describes its length."
  [prefix-or-len frame]
  (bytes/wrap-finite-block
    (if (number? prefix-or-len)
      (codecs/constant-prefix prefix-or-len)
      (compile-frame prefix-or-len))
    (compile-frame frame)))

(defn string
  "Defines a frame which contains a string.  The charset must be a keyword,
   such as :utf-8 or :ascii.  Available options are :length and :delimiters.

   A string with :length specified is of finite size:

   (string :utf-8 :length 3)

   A string with :delimiters specified is terminated by one or more delimiters:

   (string :utf-8 :delimiters [\"\r\n\" \"\n\"])"
  [charset & {:as options}]
  (let [charset (name charset)]
    (cond
      (:length options)
      (string/finite-string-codec charset (:length options))

      (:delimiters options)
      (bytes/delimited-codec
	(->> (:delimiters options)
	  (map #(if (string? %) (.getBytes % charset) %))
	  (map to-byte-buffer))
	(string/string-codec charset))

      :else
      (string/string-codec charset))))

(defn string-integer
  [charset & {:as options}]
  (let [codec (apply string charset (apply concat options))
	read-codec (compose-callback
		     codec
		     (fn [n b]
		       [true (Long/parseLong (str n)) b]))]
    (reify
      Reader
      (read-bytes [_ b]
	(read-bytes read-codec b))
      Writer
      (sizeof [_]
	nil)
      (write-bytes [_ _ v]
	(write-bytes codec nil (str (long v)))))))

(defn string-float
  [charset & {:as options}]
  (let [codec (apply string charset (apply concat options))
	read-codec (compose-callback
		     codec
		     (fn [n b]
		       [true (Double/parseDouble (str n)) b]))]
    (reify
      Reader
      (read-bytes [_ b]
	(read-bytes read-codec b))
      Writer
      (sizeof [_]
	nil)
      (write-bytes [_ _ v]
	(write-bytes codec nil (str (double v)))))))

(defn header
  "A header is a frame which describes the frame that follows.  The decoded value
   from the header frame will be passed into 'header->body,' which will return the
   resulting codec for the body.  When encoding, the value of the body will be passed
   into 'body->header,' which will return the resulting value for the header."
  [frame header->body body->header]
  (codecs/header
    (compile-frame frame)
    header->body
    body->header))

;;;

(defn prefix
  "A prefix is a specialized form of header, which only describes the length of the sequence
   that follows.  It is only meant to be used in the context of 'finite-frame' or 'repeated'.
   A prefix may be as simple as a primitive type:

   (prefix :int32)

   But may also be a more complex frame:

   (prefix
     [(string :utf-8 :delimiters [\"\0\"]) :int64]
     second
     (fn [n] [\"hello\" n]))

   For complex prefixes, 'to-integer' must take the value of the header and return the length
   of the sequence that follows, and 'from-integer' must take the length of the sequence and return
   the value of the prefix."
  ([primitive]
     (prefix primitive identity identity))
  ([signature to-integer from-integer]
     (codecs/prefix (compile-frame signature) to-integer from-integer)))

(defn repeated
  "Describes a sequence of frames.  By default, the sequence is prefixed with a 32-bit integer
   describing the length of the sequence.  However, specifying a custom :prefix or :delimiters that
   terminate the sequence is also allowed."
  [frame & {:as options}]
  (let [codec (compile-frame frame)]
    (cond
      (:delimiters options)
      (bytes/wrap-delimited-sequence
	(map to-byte-buffer (:delimiters options))
	codec)
      
      :else
      (codecs/wrap-prefixed-sequence
	(or (:prefix options) (:int32 primitive-codecs))
	codec))))
