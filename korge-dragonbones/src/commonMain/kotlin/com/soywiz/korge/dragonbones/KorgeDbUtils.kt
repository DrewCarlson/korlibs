package com.soywiz.korge.dragonbones

import com.dragonbones.model.*
import com.soywiz.korge.render.*
import com.soywiz.korim.format.*
import com.soywiz.korio.file.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.serialization.json.*

suspend fun VfsFile.readDbAtlas(factory: KorgeDbFactory): TextureAtlasData {
	val jsonFile = this
	val tex = jsonFile.readString()
	val texInfo = Json.parse(tex)!!
	val imageFile = jsonFile.parent[Dynamic { texInfo["imagePath"].str }]
	val image = imageFile.readBitmapOptimized().mipmaps()
	return factory.parseTextureAtlasData(Json.parse(tex)!!, image)
}

suspend fun VfsFile.readDbSkeleton(factory: KorgeDbFactory): DragonBonesData {
	val ske = Json.parse(this.readString())!!
	return factory.parseDragonBonesData(ske) ?: error("Can't load skeleton $this")
}

suspend fun VfsFile.readDbSkeletonAndAtlas(factory: KorgeDbFactory): DragonBonesData {
	val atlas = this.parent[this.basename.replace("_ske", "_tex")].readDbAtlas(factory)
	val skel = this.readDbSkeleton(factory)
	return skel
}

fun DragonBonesData.buildFirstArmatureDisplay(factory: KorgeDbFactory) =
	factory.buildArmatureDisplay(this.armatureNames.firstOrNull() ?: error("DbData doesn't have armatures"))
