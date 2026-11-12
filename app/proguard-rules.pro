# ===== 老人桌面 R8 规则 =====
# 保留行号信息：崩溃堆栈仍能定位到具体代码行（只留 SourceFile 不改写文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Application / Activity / Service / Receiver 由 AGP 依据 Manifest 自动 keep，
# 这里只对入口类做兜底，避免将来改 manifest 写法时被裁掉。
-keep class com.example.elderlauncher.blue.core.ElderApp { *; }

# 第三方库（Material / AppCompat）都自带 consumer rules，无需重复声明。
-dontwarn org.jetbrains.annotations.**
