# Orbital release rules.
#
# The defaults (proguard-android-optimize.txt) plus aapt's generated keeps
# cover this app: MainActivity, NowPlayingListener and TurnOffService are
# manifest-declared (kept by aapt rules), the app uses no reflection on its
# own classes (the one reflective call targets a system status-bar object),
# and there are no JSON/native bridges to protect. Add keeps here only if a
# future library or reflective lookup needs them.
