# smbj (SMB shares, US-62). Kerberos (org.ietf.jgss) and mbassador's EL support (javax.el) are
# optional paths we never use; Android ships neither.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
# smbj's internal event bus (mbassador) finds listener methods by annotation at runtime.
-keepattributes *Annotation*
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}
