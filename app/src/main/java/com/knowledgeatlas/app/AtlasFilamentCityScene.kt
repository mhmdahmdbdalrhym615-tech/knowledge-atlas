package com.knowledgeatlas.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.filament.LightManager
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen PBR city scene. Imported GLB buildings replace the old
 * procedural-only skyline; rigged GLB residents carry skinning and animation.
 * Repeated models share source resources through Filament instancing.
 */
@Composable
fun AtlasFilamentCityScene(modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    val cameraHome = Position(x = 0f, y = 15.5f, z = 26f)
    val cityTarget = Position(x = 0f, y = 1.3f, z = 0f)
    val camera = rememberCameraNode(engine) {
        position = cameraHome
        lookAt(cityTarget)
    }
    val cameraManipulator = rememberCameraManipulator(
        orbitHomePosition = cameraHome,
        targetPosition = cityTarget
    )
    val sun = rememberMainLightNode(engine)

    val promenade = rememberInfiniteTransition(label = "atlas-resident-routes")
    val residentA by promenade.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(42000), RepeatMode.Restart),
        label = "resident-a"
    )
    val residentB by promenade.animateFloat(
        initialValue = 92f, targetValue = 452f,
        animationSpec = infiniteRepeatable(tween(51000), RepeatMode.Restart),
        label = "resident-b"
    )
    val residentC by promenade.animateFloat(
        initialValue = 184f, targetValue = 544f,
        animationSpec = infiniteRepeatable(tween(47000), RepeatMode.Restart),
        label = "resident-c"
    )
    val residentD by promenade.animateFloat(
        initialValue = 276f, targetValue = 636f,
        animationSpec = infiniteRepeatable(tween(56000), RepeatMode.Restart),
        label = "resident-d"
    )
    val residentAngles = listOf(residentA, residentB, residentC, residentD)
    val whaleA by promenade.animateFloat(
        initialValue = 18f, targetValue = 378f,
        animationSpec = infiniteRepeatable(tween(86000), RepeatMode.Restart),
        label = "whale-a"
    )
    val whaleB by promenade.animateFloat(
        initialValue = 195f, targetValue = 555f,
        animationSpec = infiniteRepeatable(tween(104000), RepeatMode.Restart),
        label = "whale-b"
    )
    val bubbleMotion by promenade.animateFloat(
        initialValue = 0f, targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(tween(13000), RepeatMode.Restart),
        label = "bubble-motion"
    )

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = camera,
        cameraManipulator = cameraManipulator,
        mainLightNode = sun
    ) {
        // Independent animated instances; source mesh data is shared to reduce memory.
        val residents = remember(modelLoader) {
            modelLoader.createInstancedModel(
                assetFileLocation = "models/atlas_citizen.glb",
                count = 4
            )
        }
        val towers = remember(modelLoader) {
            modelLoader.createInstancedModel(
                assetFileLocation = "models/atlas_tower_a.glb",
                count = 2
            )
        }
        val midRiseA = remember(modelLoader) {
            modelLoader.createInstancedModel(
                assetFileLocation = "models/atlas_building_a.glb",
                count = 3
            )
        }
        val midRiseB = remember(modelLoader) {
            modelLoader.createInstancedModel(
                assetFileLocation = "models/atlas_building_f.glb",
                count = 3
            )
        }
        val whales = remember(modelLoader) {
            modelLoader.createInstancedModel(
                assetFileLocation = "models/atlas_whale.glb",
                count = 2
            )
        }
        val coralClusters = remember(modelLoader) {
            modelLoader.createInstancedModel(
                assetFileLocation = "models/atlas_coral.glb",
                count = 8
            )
        }
        val shipwreck = remember(modelLoader) {
            modelLoader.createModelInstance("models/atlas_shipwreck.glb")
        }
        val seabedCache = remember(modelLoader) {
            modelLoader.createModelInstance("models/atlas_ocean_chest.glb")
        }

        val abyssFloor = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0xFF09212D), metallic = 0.58f, roughness = 0.34f
            )
        }
        val darkAlloy = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0xFF174452), metallic = 0.82f, roughness = 0.22f
            )
        }
        val blueGlass = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0xFF287D91), metallic = 0.36f, roughness = 0.12f
            )
        }
        val luminousTrim = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0xFF25C6CF), metallic = 0.62f, roughness = 0.18f
            )
        }
        val waterGlass = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0x294DB8D0), metallic = 0.08f, roughness = 0.035f, reflectance = 0.92f
            )
        }
        val blueCaustic = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0x684AD7ED), metallic = 0.14f, roughness = 0.14f, reflectance = 0.85f
            )
        }
        val bubbleMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                Color(0x7899F4FF), metallic = 0.18f, roughness = 0.03f, reflectance = 0.92f
            )
        }

        CubeNode(
            size = Size(28f, 0.42f, 28f),
            position = Position(x = 0f, y = -0.32f, z = 0f),
            materialInstance = abyssFloor
        )
        CylinderNode(
            radius = 9.3f, height = 0.34f,
            position = Position(x = 0f, y = -0.03f, z = 0f),
            materialInstance = darkAlloy
        )
        CylinderNode(
            radius = 5.4f, height = 0.34f,
            position = Position(x = 0f, y = 0.19f, z = 0f),
            materialInstance = luminousTrim
        )
        CylinderNode(
            radius = 2.7f, height = 0.62f,
            position = Position(x = 0f, y = 0.55f, z = 0f),
            materialInstance = darkAlloy
        )
        CylinderNode(
            radius = 1.85f, height = 3.2f,
            position = Position(x = 0f, y = 2.38f, z = 0f),
            materialInstance = blueGlass
        )
        CylinderNode(
            radius = 1.18f, height = 1.8f,
            position = Position(x = 0f, y = 4.85f, z = 0f),
            materialInstance = darkAlloy
        )
        SphereNode(
            radius = 1.22f,
            position = Position(x = 0f, y = 6.15f, z = 0f),
            materialInstance = luminousTrim,
            stacks = 18,
            slices = 24
        )
        CylinderNode(
            radius = 0.12f, height = 1.3f,
            position = Position(x = 0f, y = 7.38f, z = 0f),
            materialInstance = luminousTrim
        )

        // Imported GLB buildings include authored geometry and material assignments.
        if (towers.size >= 2) {
            ModelNode(
                towers[0], scaleToUnits = 7.0f,
                position = Position(x = -8.0f, y = 0f, z = -6.8f),
                rotation = Rotation(y = 24f)
            )
            ModelNode(
                towers[1], scaleToUnits = 7.0f,
                position = Position(x = 8.1f, y = 0f, z = 6.7f),
                rotation = Rotation(y = 204f)
            )
        }
        if (midRiseA.size >= 3) {
            ModelNode(midRiseA[0], scaleToUnits = 5.3f,
                position = Position(x = 8.0f, y = 0f, z = -6.8f),
                rotation = Rotation(y = 90f))
            ModelNode(midRiseA[1], scaleToUnits = 5.3f,
                position = Position(x = -8.1f, y = 0f, z = 6.8f),
                rotation = Rotation(y = -90f))
            ModelNode(midRiseA[2], scaleToUnits = 4.8f,
                position = Position(x = 0f, y = 0f, z = -9.7f))
        }
        if (midRiseB.size >= 3) {
            ModelNode(midRiseB[0], scaleToUnits = 4.8f,
                position = Position(x = 0f, y = 0f, z = 9.6f),
                rotation = Rotation(y = 180f))
            ModelNode(midRiseB[1], scaleToUnits = 4.7f,
                position = Position(x = -10.5f, y = 0f, z = 0f),
                rotation = Rotation(y = 90f))
            ModelNode(midRiseB[2], scaleToUnits = 4.7f,
                position = Position(x = 10.5f, y = 0f, z = 0f),
                rotation = Rotation(y = -90f))
        }

        // Skinned residents play the animation stored inside the GLB model.
        val routeRadii = floatArrayOf(4.2f, 5.0f, 4.2f, 5.0f)
        residents.forEachIndexed { index, resident ->
            val angle = residentAngles[index]
            val radians = angle * (PI.toFloat() / 180f)
            val x = cos(radians) * routeRadii[index]
            val z = sin(radians) * routeRadii[index]
            ModelNode(
                modelInstance = resident,
                autoAnimate = true,
                scaleToUnits = 1.75f,
                centerOrigin = Position(x = 0f, y = -1f, z = 0f),
                position = Position(x = x, y = 0.18f, z = z),
                rotation = Rotation(y = -angle + 90f)
            )
        }

        // A small service-drone cluster adds scale cues without a heavy crowd draw cost.
        for (index in 0 until 3) {
            val angle = (index * 120f) * (PI.toFloat() / 180f)
            val x = cos(angle) * 7.1f
            val z = sin(angle) * 7.1f
            CylinderNode(
                radius = 0.23f, height = 0.42f,
                position = Position(x = x, y = 0.32f, z = z),
                materialInstance = blueGlass,
                sideCount = 12
            )
            SphereNode(
                radius = 0.17f,
                position = Position(x = x, y = 0.62f, z = z),
                materialInstance = luminousTrim,
                stacks = 8,
                slices = 12
            )
        }

        LightNode(
            type = LightManager.Type.POINT,
            intensity = 1800f,
            position = Position(x = 0f, y = 5.5f, z = 0f),
            apply = {
                color(0.10f, 0.76f, 0.88f)
                falloff(18f)
            }
        )
        // A transparent water envelope adds depth around the city.
        SphereNode(
            radius = 19.5f,
            position = Position(x = 0f, y = 1.7f, z = 0f),
            materialInstance = waterGlass,
            stacks = 28,
            slices = 40
        )

        // Imported shipwreck and seabed relics.
        shipwreck?.let { wreck ->
            ModelNode(
                modelInstance = wreck,
                scaleToUnits = 7.5f,
                position = Position(x = -12.0f, y = 0.12f, z = 7.8f),
                rotation = Rotation(x = -5f, y = 38f, z = 8f)
            )
        }
        seabedCache?.let { chest ->
            ModelNode(
                modelInstance = chest,
                scaleToUnits = 1.2f,
                position = Position(x = 8.8f, y = 0.08f, z = -3.2f),
                rotation = Rotation(y = -28f)
            )
        }

        // Eight imported coral clusters at different elevations and scales.
        val coralSites = listOf(
            floatArrayOf(-12.8f, 0.2f, -6.5f, 2.2f),
            floatArrayOf(-10.8f, 0.15f, 10.0f, 1.8f),
            floatArrayOf(-6.2f, 0.15f, -12.1f, 1.6f),
            floatArrayOf(5.9f, 0.18f, -12.0f, 2.0f),
            floatArrayOf(12.2f, 0.12f, 8.2f, 1.9f),
            floatArrayOf(11.6f, 0.12f, -8.7f, 1.7f),
            floatArrayOf(-13.1f, 0.12f, 2.2f, 1.5f),
            floatArrayOf(2.0f, 0.12f, 13.0f, 1.8f)
        )
        coralClusters.forEachIndexed { index, coral ->
            val site = coralSites[index]
            ModelNode(
                modelInstance = coral,
                scaleToUnits = site[3],
                position = Position(x = site[0], y = site[1], z = site[2]),
                rotation = Rotation(y = index * 43f, x = (index % 3 - 1) * 4f)
            )
        }

        // Two large marine animals follow separate arcs around the city.
        whales.forEachIndexed { index, whale ->
            val angle = if (index == 0) whaleA else whaleB
            val radians = angle * (PI.toFloat() / 180f)
            val radius = if (index == 0) 15.0f else 16.7f
            val x = cos(radians) * radius
            val z = sin(radians) * radius
            val depth = 3.5f + sin(radians * 1.7f + index) * 0.75f
            ModelNode(
                modelInstance = whale,
                autoAnimate = true,
                scaleToUnits = if (index == 0) 5.5f else 4.2f,
                position = Position(x = x, y = depth, z = z),
                rotation = Rotation(y = -angle + 90f, z = sin(radians * 2f) * 3f)
            )
        }

        // Soft underwater light columns and a vertical caustic field.
        for (index in 0 until 5) {
            val angle = index * (PI.toFloat() * 2f / 5f)
            val x = cos(angle) * 7.7f
            val z = sin(angle) * 7.7f
            CylinderNode(
                radius = 0.16f, height = 10.5f,
                position = Position(x = x, y = 5.3f, z = z),
                materialInstance = blueCaustic,
                sideCount = 20
            )
        }

        // Slow bubbles rise continuously through the water column.
        for (index in 0 until 18) {
            val angle = index * 2.39996f
            val x = cos(angle) * (6.2f + (index % 4) * 1.15f)
            val z = sin(angle) * (6.2f + (index % 4) * 1.15f)
            val phase = bubbleMotion + index * 0.71f
            val height = 0.5f + ((phase + index * 0.37f) % 6.0f)
            val radius = 0.045f + (index % 3) * 0.022f
            SphereNode(
                radius = radius,
                position = Position(x = x, y = height, z = z),
                materialInstance = bubbleMaterial,
                stacks = 7,
                slices = 10
            )
        }

        LightNode(
            type = LightManager.Type.POINT,
            intensity = 850f,
            position = Position(x = -7f, y = 4f, z = 4f),
            apply = {
                color(0.22f, 0.42f, 1f)
                falloff(12f)
            }
        )
    }
}
