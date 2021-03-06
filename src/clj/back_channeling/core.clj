(ns back-channeling.core
  (:gen-class)
  (:use [hiccup.core :only [html]]
        [hiccup.page :only [include-js]]
        [environ.core :only [env]]
        [ring.util.response :only [resource-response content-type header redirect]]
        [ring.middleware.defaults :only [wrap-defaults site-defaults]]
        [ring.middleware.reload :only [wrap-reload]]

        [buddy.auth :only [authenticated?]]
        [buddy.auth.backends.session :only [session-backend]]
        [buddy.auth.backends.token :only [token-backend]]
        [buddy.auth.middleware :only [wrap-authentication wrap-authorization]]
        [buddy.auth.accessrules :only [wrap-access-rules]]
        (back-channeling [resources :only [api-routes]]
                         [layout :only [layout]]))
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [liberator.dev]
            [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [defroutes GET POST routing] :as compojure]
            [compojure.route :as route]
            [buddy.auth.http :as http]
            (back-channeling [server :as server]
                             [style :as style]
                             [model :as model]
                             [signup :as signup]
                             [token :as token]))
  (:import [java.io FileInputStream]
           [java.util UUID]))

(defn index-view [req]
  (layout req
   [:div#app.ui.page.full.height]
   (include-js (str "/js/back-channeling"
                    (when-not (:dev env) ".min") ".js"))))

(defn login-view [req]
  (layout
   req
   [:div.ui.middle.aligned.center.aligned.login.grid
    [:div.column
     [:h2.ui.header
      [:div.content
       [:img.ui.image {:src "/img/logo.png"}]]]
     [:form.ui.large.login.form
      (merge {:method "post"}
             (when (= (:request-method req) :post)
               {:class "error"}))
      [:div.ui.stacked.segment
       [:div.ui.error.message
        [:p "User name or password is wrong."]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.user.icon]
         [:input {:type "text" :name "username" :placeholder "User name"}]]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.lock.icon]
         [:input {:type "password" :name "password" :placeholder "Password"}]]]
       [:button.ui.fluid.large.teal.submit.button {:type "submit"} "Login"]]]
     [:div.ui.message
      "New to us? " [:a {:href "/signup"} "Sign up"]]]]))

(defn auth-by-password [username password]
  (when (and (not-empty username) (not-empty password))
    (model/query '{:find [(pull ?s [:*]) .]
                   :in [$ ?uname ?passwd]
                   :where [[?s :user/name ?uname]
                           [?s :user/salt ?salt]
                           [(concat ?salt ?passwd) ?passwd-seq]
                           [(into-array Byte/TYPE ?passwd-seq) ?passwd-bytes]
                           [(buddy.core.hash/sha256 ?passwd-bytes) ?hash]
                           [(buddy.core.codecs/bytes->hex ?hash) ?hash-hex]
                           [?s :user/password ?hash-hex]]} username password)))

(defroutes app-routes
  (GET "/" req (index-view req))
  (GET "/login" req (login-view req))
  (POST "/login" {{:keys [username password]} :params :as req}
    (if-let [user (auth-by-password username password)]
        (-> (redirect (get-in req [:query-params "next"] "/"))
            (assoc-in [:session :identity] (select-keys user [:user/name :user/email])))
        (login-view req)))
  (GET "/signup" req
    (signup/signup-view req))
  (POST "/signup" req
    (signup/signup (select-keys (clojure.walk/keywordize-keys (:params req))
                                [:user/email :user/name :user/password :user/token])))
  
  (GET "/logout" []
    (-> (redirect "/")
        (assoc :session {})))
  
  (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                (content-type "text/javascript")))
  (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
  (compojure/context "/api" [] api-routes)
  (GET "/css/back-channeling.css" [] (-> {:body (style/build)}
                                         (content-type "text/css")))
  (GET ["/voice/:thread-id/:filename" :thread-id #"\d+" :filename #"[0-9a-f\-]+\.ogg"] [thread-id filename]
    (let [content-type (cond (.endsWith filename ".wav") "audio/wav"
                             (.endsWith filename ".ogg") "audio/ogg"
                             :else (throw (IllegalArgumentException. filename)))]
      {:headers {"content-type" content-type}
       :body (FileInputStream. (str "voices/" thread-id "/" filename))}))

  (route/resources "/")
  (route/not-found "Not found."))

(defmulti handle-command (fn [msg ch] (first msg)))

(defmethod handle-command :leave [[_ message] ch]
  (log/info "disconnect" ch)
  (server/broadcast-message "/ws" [:leave {:user/name (:user/name message)
                                           :user/email (:user/email message)}]))
(defmethod handle-command :call [[_ message] ch]
  (log/info "call from " (:from message) " to " (:to message))
  (server/multicast-message "/ws" [:call message]
                            (:to message)))

(defn api-access? [req]
  (if-let [accept (get-in req [:headers "accept"])]
    (or (.contains accept "application/json")
        (.contains accept "application/edn"))))

(def access-rules [{:pattern #"^(/|/api/(?!token).*)$" :handler authenticated?}])

(def session-base (session-backend
                      {:unauthorized-handler
                       (fn [req meta]
                         (if (api-access? req)
                           (if (authenticated? req)
                             (http/response "Permission denied" 403)
                             (http/response "Unauthorized" 401))
                           (if (authenticated? req)
                             (redirect "/login")
                             (redirect (format "/login?next=%s" (:uri req))))))}))

(def token-base (token-backend
                {:authfn
                 (fn [req token]
                   (try
                     (token/auth-by (UUID/fromString token))
                     (catch Exception e)))}))

(defn -main [& args]
  (model/create-schema)
  (server/run-server
   (-> app-routes
       (wrap-access-rules {:rules access-rules :policy :allow})
       (wrap-authorization  session-base)
       (wrap-authentication token-base session-base)
       (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))
       (wrap-reload))
   :port (Integer/parseInt (or (:back-channeling-port env)
                               (:port env)
                               "3009"))
   :websockets [{:path "/ws"
                 :on-message (fn [ch message]
                               (handle-command (edn/read-string message) ch))
                 :on-close (fn [ch close-reason]
                             (log/info "disconnect" ch "for" close-reason)
                             (handle-command [:leave (server/find-user-by-channel "/ws" ch)] ch))}]))
