# Ofuscacao (R8): o codigo do app vira nomes sem sentido para dificultar quem tenta abrir o APK.
# Aqui ficam SO as excecoes: classes chamadas pelo NOME por outro processo ou pelo sistema.

# Daemon de toque (motor interno): o app_process chama main() pelo nome da classe
-keep class com.ruan.uberareafilter.adb.StandaloneTapDaemon { public static void main(java.lang.String[]); }

# Servico do Shizuku (criado pelo nome) e a interface AIDL dele
-keep class com.ruan.uberareafilter.shizuku.ShizukuTapUserService { <init>(...); *; }
-keep class com.ruan.uberareafilter.shizuku.IShizukuTapService { *; }
-keep class com.ruan.uberareafilter.shizuku.IShizukuTapService$* { *; }

# Bibliotecas que usam reflexao
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
# Tira os nomes de arquivo/linha dos erros (mais dificil de ler o codigo)
-renamesourcefileattribute SourceFile

# Paginas web (painel de Ganhos, mapa) chamam o app pelo nome dos metodos
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
