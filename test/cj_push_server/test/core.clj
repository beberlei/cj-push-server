(ns cj-push-server.test.core
  (:use [cj-push-server.core])
  (:use [clojure.test]))

(deftest replace-me ;; FIXME: write
  (def xml (slurp "sample.xml"))
  (with-namespace-context {"atom" "http://www.w3.org/2005/Atom"}
                         ($x:text "//atom:title" xml-doc))
  (is false "No tests have been written."))
