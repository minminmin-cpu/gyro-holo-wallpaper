# WallpaperService 由系统按类名反射启动，必须保留。
-keep class com.example.holowallpaper.wallpaper.HoloWallpaperService { *; }
-keepnames class * extends android.service.wallpaper.WallpaperService
