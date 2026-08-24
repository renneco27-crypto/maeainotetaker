plugins {
    id("com.android.asset-pack")
}

android {
    namespace = "com.cortesnotetaker.app.whispermodelpack"
    compileSdk = 35

    assetPack {
        packName = "whisperModelPack"
        dynamicDelivery {
            // Install-time delivery for offline-first app
            deliveryType = "install-time"
        }
    }
}

dependencies {
    // No dependencies needed - this is just an asset pack
}