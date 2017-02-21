(defproject beerbot "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [mount "0.1.11"]
                 [com.github.Ullink/simple-slack-api "e8a9423"]
                 [org.glassfish.tyrus.bundles/tyrus-standalone-client "1.13"]
                 [clj-http "2.3.0"]
                 [instaparse "1.4.3"]
                 [incanter "1.5.7"]
                 [com.taoensso/nippy "2.12.2"]]
  :repositories {"ullink" "http://ulcentral:8081/artifactory/repo"
                 "jitpack" "https://jitpack.io"}
  :main ^:skip-aot beerbot.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
