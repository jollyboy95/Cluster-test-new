H7 Factory Theme Control UI patch for jollyboy95/Cluster-test-new

Run from repository root after extracting ZIP:
  bash h7_factory_theme_setter_patch/install.sh .
  git add App/
  git commit -m "Add factory theme control UI"
  git pull --rebase origin main
  git push origin main

Includes theme selection for Guide 4, Super Sport 7, Mecha 10, Super Sport Plus 11.
The real SET_DATA permission is requested but requires factory signature/privileged install.
This is a UI and permission-gated setter scaffold. It DOES NOT change the cluster.
No speculative Binder transactions, Settings writes, CAN messages, or permission bypasses.
