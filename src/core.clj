;; Copyright (c) 2020 DINUM, Bastien Guerry <bastien.guerry@data.gouv.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE

(ns core
  (:require [hickory.core :as h]
            [hickory.select :as hs]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [babashka.curl :as curl]
            [java-time :as t])
  (:gen-class))

;; Leave this to true if you are not allowed to update the dataset
(def testing true)

(def data-url "https://www.santepubliquefrance.fr/maladies-et-traumatismes/maladies-et-infections-respiratoires/infection-a-coronavirus/articles/infection-au-nouveau-coronavirus-sars-cov-2-covid-19-france-et-monde")

;; https://www.data.gouv.fr/fr/admin/dataset/5e689ada634f4177317e4820/
(def datagouv-api "https://www.data.gouv.fr/api/1")
(def datagouv-api-token (System/getenv "DATAGOUV_API_TOKEN"))
(def csv-file-path (str (System/getProperty "user.home") "/covid19/covid19.csv"))
(def svg-file-path (str (System/getProperty "user.home") "/covid19/covid19.svg"))
(def dataset "5e689ada634f4177317e4820")
(def resource-csv "fa9b8fc8-35d5-4e24-90eb-9abe586b0fa5")
(def resource-svg "5ba293c5-30de-4d36-9d3c-cc2b2fd9faae")

(defn- rows->maps [csv]
  (let [headers (map keyword (first csv))
        rows    (rest csv)]
    (map #(zipmap headers %) rows)))

(defn csv-to-vega-data [csv]
  (flatten
   (map (fn [row]
          (let [date (:Date row)]
            (filter
             seq (map (fn [[k v]]
                        (if-not (= :Date k)
                          {:region (name k) :cases v :date date}))
                      row))))
        (rows->maps csv))))

(defn- temp-json-file
  "Convert `clj-vega-spec` to json and store it as tmp file."
  [clj-vega-spec]
  (let [tmp-file (java.io.File/createTempFile "vega." ".json")]
    (.deleteOnExit tmp-file)
    (with-open [file (io/writer tmp-file)]
      (json/write clj-vega-spec file))
    (.getAbsolutePath tmp-file)))

(defn vega-spec [csv]
  {:title    "Cas confirmés de contamination au COVID19 (Source: Santé Publique France)"
   :data     {:values (csv-to-vega-data csv)}
   :encoding {:x     {:field "date" :type "temporal"
                      :axis  {:title      "Dates"
                              :labelAngle 0}}
              :y     {:field "region" :type "ordinal"
                      :axis  {:title "Régions"}}
              :size  {:field  "cases"
                      :type   "quantitative"
                      :legend {:title      "Cas confirmés"
                               :clipHeight 50
                               :padding    20}
                      :scale  {:range [0 2000]}}
              :color {:field  "cases"
                      :type   "nominal"
                      :legend false}}
   :width    1200
   :height   600
   :mark     {:type        "circle"
              :opacity     0.8
              :stroke      "black"
              :strokeWidth 1
              :tooltip     true}})

(defn vega-chart! [csv]
  (sh/sh "vl2svg" (temp-json-file (vega-spec csv))
         svg-file-path))

(def datagouv-endpoint-format
  (str datagouv-api "/datasets/" dataset
       "/resources/%s/upload/"))

(def datagouv-api-headers
  {:headers {"Accept"    "application/json"
             "X-Api-Key" datagouv-api-token}})

(defn upload-to-datagouv []
  (let [csv-output
        (curl/post
         (format datagouv-endpoint-format resource-csv)
         (merge datagouv-api-headers
                {:form-params {"file" (str "@" csv-file-path)}}))
        svg-output
        (curl/post
         (format datagouv-endpoint-format resource-svg)
         (merge datagouv-api-headers
                {:form-params {"file" (str "@" svg-file-path)}}))]
    {:csv-output (:success (json/read-str csv-output :key-fn keyword))
     :svg-output (:success (json/read-str svg-output :key-fn keyword))}))

(defn get-covid19-raw-data []
  (if-let [data (try (curl/get data-url)
                     (catch Exception _ nil))]
    (let [out (-> data h/parse h/as-hickory
                  (as-> d (hs/select (hs/class "content__table-inner") d))
                  first :content first :content)]
      (:content (second out)))))

(defn clean-up-scraped-value [s]
  (if-not (string? s)
    0
    (clojure.string/replace s #" |\*" "")))

(defn get-covid19-data []
  (filter (fn [[_ c]] (string? c))
          (map (fn [[l r]]
                 (let [a (first (:content l))
                       b (clean-up-scraped-value
                          (first (:content r)))]
                   [a b]))
               (map :content (get-covid19-raw-data)))))

(defn arrange-data [data]
  [(cons "Date" (map first data))
   (cons (t/format "yyyy/MM/dd" (t/zoned-date-time))
         (map second data))])

(defn -main []
  (let [hist   (try (with-open [reader (io/reader csv-file-path)]
                      (doall (csv/read-csv reader)))
                    (catch Exception _
                      (println "No initial covid19.csv file, creating new")))
        new    (arrange-data (get-covid19-data))
        merged (concat (or (not-empty (take 1 hist)) (take 1 new))
                       (distinct (concat (rest hist) (rest new))))]
    ;; (vega-chart! (drop-last merged))
    (if (= (drop 1 (last hist)) (drop 1 (last new)))
      (println "No update available")
      (do (with-open [writer (io/writer csv-file-path)]
            (csv/write-csv writer merged))
          (println "Wrote covid19.csv")
          (vega-chart! merged)
          (println "Wrote covid19.svg")
          (cond testing (println "Testing: skip uploading")
                (every? true? (vals (upload-to-datagouv)))
                (println "covid19 resources uploaded to data.gouv.fr")
                :else
                (println "Error while trying to upload covid19.csv"))
          (if-not testing (System/exit 0))))))

;; (-main)
