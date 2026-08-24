plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("whisperModelPack")
    dynamicDelivery {
        deliveryType.set(com.android.build.api.dsl.DeliveryType.INSTALL_TIME)
    }
}