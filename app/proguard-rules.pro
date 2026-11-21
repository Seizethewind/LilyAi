# Remote workspaces (SFTP / FTPS / FTP). Release builds shrink code, so keep the SSH library whole and
# ignore the optional libraries it can use but this app does not ship.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
# Tink (via androidx.security.crypto) references annotation-only classes that are not shipped.
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# In-app terminal (Termux's terminal-view / terminal-emulator). Its native code finds the Java classes and fields by name,
# so release builds must not rename or remove them.
-keep class com.termux.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
