(ns joyride.extension
  (:require ["path" :as path]
            ["vscode" :as vscode]
            [joyride.config :as conf]
            [joyride.db :as db]
            [joyride.life-cycle :as life-cycle]
            [joyride.nrepl :as nrepl]
            [joyride.sci :as jsci]
            [joyride.scripts-menu :refer [show-script-picker+]]
            [joyride.utils :as utils :refer [info jsify vscode-read-uri+]]
            [joyride.when-contexts :as when-contexts]
            [promesa.core :as p]
            [sci.core :as sci]))

(defn- register-command! [^js context command-id var]
  (let [disposable (vscode/commands.registerCommand command-id var)]
    (swap! db/!app-db update :disposables conj disposable)
    (.push (.-subscriptions context) disposable)))

(defn- clear-disposables! []
  (swap! db/!app-db assoc :disposables [])
  (p/run! (fn [^js disposable]
            (.dispose disposable))
          (:disposables @db/!app-db)))

(defn run-code
  ([]
   (p/let [input (vscode/window.showInputBox #js {:title "Run Code"
                                                  ;; "(require '[\"vscode\" :as vscode]) (vscode/window.showInformationMessage \"Hello World!\" [\"Hi there\"])"
                                                  :placeHolder "(inc 41)"
                                                  :prompt "Enter some code to be evaluated"})]
     (when input
       (run-code input))))
  ([code]
   (p/let [result (jsci/eval-string code)]
     (utils/say-result result))))

(defn choose-file [default-uri]
  (vscode/window.showOpenDialog #js {:canSelectMany false
                                     :defaultUri default-uri
                                     :openLabel "Open script"}))

(defn run-script+
  ([title base-path scripts-path]
   (p/let [picked-script (show-script-picker+ title base-path scripts-path)]
     (when picked-script
       (run-script+ title base-path scripts-path (:relative-path picked-script)))))
  ([title base-path scripts-path script-path]
   (-> (p/let [abs-path (path/join base-path scripts-path script-path)
               script-uri (vscode/Uri.file abs-path)
               code (vscode-read-uri+ script-uri)]
         (swap! db/!app-db assoc :invoked-script abs-path)
         (sci/with-bindings {sci/file abs-path}
           (jsci/eval-string code)))
       (p/handle (fn [result error]
                   (swap! db/!app-db assoc :invoked-script nil)
                   (if error
                     (binding [utils/*show-when-said?* true]
                       (utils/say-error (str title " Failed: " script-path " " (.-message error))))
                     (do (utils/say-result (str script-path " evaluated.") result)
                         result)))))))

(def run-workspace-script-args ["Run Workspace Script"
                                vscode/workspace.rootPath
                                conf/workspace-scripts-path])

(defn run-workspace-script+
  ([]
   (apply run-script+ run-workspace-script-args))
  ([script]
   (apply run-script+ (conj run-workspace-script-args script))))

(def run-user-script-args ["Run User Script"
                           conf/user-config-path
                           conf/user-scripts-path])

(defn run-user-script+
  ([]
   (apply run-script+ run-user-script-args))
  ([script]
   (apply run-script+ (conj run-user-script-args script))))

(defn start-nrepl-server+ [root-path]
  (nrepl/start-server+ {:root-path (or root-path vscode/workspace.rootPath)}))

(def api (jsify {:startNReplServer start-nrepl-server+
                 :getContextValue (fn [k]
                                    (when-contexts/context k))}))

(defn ^:export activate [^js context]
  (when context
    (swap! db/!app-db assoc
           :output-channel (vscode/window.createOutputChannel "Joyride")
           :extension-context context)
    (binding [utils/*show-when-said?* true]
      (utils/say "🟢 Joyride VS Code with Clojure. 🚗"))
    (p/-> (life-cycle/maybe-run-init-script+ run-user-script+
                                             (:user life-cycle/init-scripts))
          (p/then
           (fn [_result]
             (life-cycle/maybe-run-init-script+ run-workspace-script+
                                                (:workspace life-cycle/init-scripts))))))
  (let [{:keys [extension-context]} @db/!app-db]
    (register-command! extension-context "joyride.runCode" #'run-code)
    (register-command! extension-context "joyride.runWorkspaceScript" #'run-workspace-script+)
    (register-command! extension-context "joyride.runUserScript" #'run-user-script+)
    (register-command! extension-context "joyride.startNReplServer" #'start-nrepl-server+)
    (register-command! extension-context "joyride.stopNReplServer" #'nrepl/stop-server)
    (register-command! extension-context "joyride.enableNReplMessageLogging" #'nrepl/enable-message-logging!)
    (register-command! extension-context "joyride.disableNReplMessageLogging" #'nrepl/disable-message-logging!)
    (when-contexts/set-context! ::when-contexts/joyride.isActive true)
    api))

(defn ^:export deactivate []
  (when-contexts/set-context! ::when-contexts/joyride.isActive false)
  (when (nrepl/server-running?)
    (nrepl/stop-server))
  (clear-disposables!))

(defn before [done]
  (-> (clear-disposables!)
      (p/then done)))

(defn after []
  (info "shadow-cljs reloaded Joyride")
  (js/console.log "shadow-cljs Reloaded"))

(comment
  (def ba (before after)))