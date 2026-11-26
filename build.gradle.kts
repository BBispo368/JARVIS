// Arquivo raiz: só declara os plugins; cada módulo aplica o que usa.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
