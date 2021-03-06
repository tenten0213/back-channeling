(ns back-channeling.components.comment
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.comment-helper :only [format-plain]])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn random-string [n]
  (->> (repeatedly #(rand-nth "0123456789abcdefghijklmnopqrstuvwxyz"))
       (take n)
       (reduce str)))

(defcomponent comment-view [comment owner {:keys [thread board-name comment-attrs] :or {comment-attrs {}}}]
  (render-state [_ {:keys [selected?]}]
    (html
     [:div.comment (merge {:data-comment-no (:comment/no comment)}
                          comment-attrs
                          (when selected? {:class "selected"}))
      (om/build avatar (get-in comment [:comment/posted-by]))
      [:div.content
       [:a.number (:comment/no comment)] ": "
       [:a.author (get-in comment [:comment/posted-by :user/name])]
       [:div.metadata
        [:span.date (.format date-format-m (get-in comment [:comment/posted-at]))]]
       [:div.text (case (get-in comment [:comment/format :db/ident])
                    :comment.format/markdown {:key (str "markdown-" (random-string 16))
                                              :dangerouslySetInnerHTML {:__html (js/marked (:comment/content comment))}}
                    :comment.format/voice [:audio {:controls true
                                                   :src (str "/voice/" (:comment/content comment))}]
                    (format-plain (:comment/content comment) :thread-id (:db/id thread) :board-name board-name))]]])))
