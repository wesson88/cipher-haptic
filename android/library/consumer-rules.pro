# 随 AAR 分发给宿主 App 的保留规则。
# 本库不使用反射、不做动态类加载，故规则极小 —— 只保留对外 API。
-keep public class com.cipherlex.haptic.CipherHaptic { public *; }
-keep public interface com.cipherlex.haptic.core.** { *; }
-keep public enum com.cipherlex.haptic.core.** { *; }
