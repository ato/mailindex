(ns net.dishevelled.mailindex.imap
  (:import (org.apache.lucene.index IndexReader IndexWriter
                                    IndexWriter$MaxFieldLength Term)
           (org.apache.lucene.search IndexSearcher BooleanQuery
                                     PhraseQuery BooleanClause$Occur TermQuery)
           (org.apache.lucene.document Document Field Field$Store
                                       Field$Index DateTools
                                       DateTools$Resolution)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.search.highlight QueryTermExtractor)
           (org.apache.lucene.store FSDirectory)
           (javax.mail Folder UIDFolder Part Message)
           (javax.mail.internet MimeMultipart)
           (com.sun.mail.imap IMAPMessage IMAPFolder))
  (:refer-clojure :exclude [line-seq])
  (:use [clojure.contrib.duck-streams]
        clojure.contrib.java-utils
        [clojure.contrib.seq-utils :only [flatten]]
        [clojure.contrib.str-utils2 :only [split] :as str]
        [net.dishevelled.mailindex :only [line-seq load-headers load-body
                                          index-message chatty
                                          handle-searches time-to-optimise?
                                          *index-delay*]])
  (:require [net.dishevelled.mailindex.fieldpool :as fieldpool])
  (:gen-class
   :main true))

(defn #^javax.mail.Session new-mail-session []
  (javax.mail.Session/getDefaultInstance (java.util.Properties.)))

(defn parse-authinfo-line [line]
  (if-let [m (re-matches #"machine (\S+) login (\S+) password (\S+)" line)]
    (rest m)))

(defn read-authinfo [host username]
  (->> (file (get-system-property "user.home") ".authinfo")
       (reader)
       (line-seq)
       (map parse-authinfo-line)
       (filter (fn [[h u p]] (and (= h host) (= u username))))
       (first)
       (last)))

(defn imap-connect [host username & [password]]
  (let [password (or password (read-authinfo host username))
        session (new-mail-session)
        store (.getStore session "imap")]
    (.connect store host username password)
    store))

(defn list-folder [#^Folder folder]
  (seq (.list folder)))

(defn folder-seq [#^javax.mail.Store store]
  (tree-seq list-folder
            list-folder
            (.getDefaultFolder store)))

(declare parse-mime-part)

(defn get-body-part [#^MimeMultipart multipart idx]
  (.getBodyPart multipart (int idx)))

(defn parse-mime-multipart [#^MimeMultipart multipart]
  (->> (range (.getCount multipart))
       (map #(get-body-part multipart %))
       (map parse-mime-part)))

(defn strip-tags [#^String html]
  (str/replace html #"<[^>]*>|&nbsp;|&#\d+;" ""))

(defn parse-mime-part [#^Part part]
  (try
   (condp re-find (.toLowerCase (.getContentType part))
     #"^text/plain" [(.getContent part)]
     #"^text/calendar" [(.getContent part)]
     #"^text/(html|xml)" [(strip-tags (.getContent part))]
     #"^multipart/" (parse-mime-multipart (.getContent part))
     #"^message/rfc822" (parse-mime-part (.getContent part))
     #"^image/" []
     #"^application/" []
     #"^text/x-bibtex" []
     #"^text/x-tex" []
     #"^text/x-java" []
     #"^text/x-patch" []
     #"^text/x-perl" []
     #"^text/x-diff" []
     #"^text/x-emacs-lisp" []
     #"^text/x-python" []
     #"^text/x-makefile" []
     #"^message/delivery-status" []
     (do
       (println "unhandled content-type: " (.getContentType part))
       []))
   (catch java.io.UnsupportedEncodingException e
     [])))

(defn parse-imap-message [#^IMAPMessage message]
  "Produce a Lucene document from an IMAP message."
  (let [#^Document doc (Document.)
        folder (.getFolder message)
        num (str (.getUID #^UIDFolder folder message))
        group (.getFullName folder)]

    (fieldpool/reset)

    ;;(.add doc (fieldpool/stored-field "filename" "..."))
    (.add doc (fieldpool/stored-field "num" num))
    (.add doc (fieldpool/stored-field "group" group))
    (.add doc (fieldpool/stored-field "id" (format "%s@%s" num group)))

    (load-headers doc (enumeration-seq (.getAllHeaderLines message)))
    (load-body doc (mapcat #(split (str %) #"\r?\n")
                           (flatten (parse-mime-part message)))
               0 0)
    doc))

(defn index-imap-folder [folder index optimise start-uid]
  "Adds any modified messages from `basedir' to `index'.
If optimise is true, optimise the index once this is done.
Any paths contained in `seen-messages' are skipped."
  (try
   (IndexWriter/unlock (FSDirectory/getDirectory index))
   (println "Indexing folder" (.getFullName folder) "starting at" start-uid)
   (.open folder Folder/READ_ONLY)
   (with-open [#^IndexWriter writer (doto (IndexWriter.
                                           index
                                           (StandardAnalyzer.)
                                           IndexWriter$MaxFieldLength/UNLIMITED)
                                      (.setRAMBufferSizeMB 20)
                                      (.setUseCompoundFile false))]
     (doseq [msg (seq (.getMessagesByUID folder start-uid UIDFolder/LASTUID))]
       (index-message writer (parse-imap-message msg)))

     (when optimise
       (chatty "Optimising"
               ;;(find-deletes index writer)
               (.optimize writer true)))
     (.getUIDNext folder))
   (catch Exception e
     (.println System/err (str "Agent got exception: " e))
     (.printStackTrace e)
     start-uid)
   (finally
    (when (.isOpen folder)
      (.close folder false)))))

(defn holds-messages? [#^Folder folder]
  (pos? (bit-and (.getType folder) Folder/HOLDS_MESSAGES)))

(defn index-imap [host username index optimise uids]
  (with-open [store (imap-connect host username)]
    (into uids
          (for [folder (folder-seq store)
                :when (holds-messages? folder)]
            (let [name (.getFullName folder)]
              [name (index-imap-folder folder index optimise
                                       (get uids name 1))])))))

(defn start-imap-indexing
  "Kick off the indexer."
  [uids host user indexfile]
  (println "Polling...")
  (let [uids (index-imap host user indexfile
                         (time-to-optimise?) (or uids {}))]
    (println "OK.  Sleepy time.")
    (Thread/sleep *index-delay*)
    (send-off *agent* start-imap-indexing host user indexfile)
    uids))

(defn -main [& args]
  (if (empty? args)
    (println "Usage: imap.clj host user index listen-port")
    (let [[host user index port] args
          indexer (agent nil)
          searcher (agent nil)]
      (send-off indexer start-imap-indexing host user index)
      (send-off searcher handle-searches index (Integer. port))
      (await indexer)
      (await searcher))))

