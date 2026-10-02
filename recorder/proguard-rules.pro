# Logcat export is a product feature in release builds too. Do not strip Log.i calls.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
