package com.spacedrift.fieldproof.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spacedrift.fieldproof.ui.camera.CameraCaptureScreen
import com.spacedrift.fieldproof.ui.components.deriveSyncSummary
import com.spacedrift.fieldproof.ui.detail.InspectionDetailScreen
import com.spacedrift.fieldproof.ui.home.HomeScreen
import com.spacedrift.fieldproof.ui.inspection.InspectionViewModel

@Composable
fun FieldProofNavHost(
    viewModel: InspectionViewModel,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            val ready by viewModel.homeReady.collectAsState()
            val summaries by viewModel.homeSummaries.collectAsState()
            val syncOperations by viewModel.syncOperations.collectAsState()
            val isOnline by viewModel.isOnline.collectAsState()

            HomeScreen(
                ready = ready,
                summaries = summaries,
                syncOperations = syncOperations,
                syncSummary = deriveSyncSummary(syncOperations, isOnline),
                onRetrySync = { viewModel.retrySync() },
                onCreateInspection = { title, location ->
                    viewModel.createInspection(title, location) { id ->
                        navController.navigate(Routes.detail(id))
                    }
                },
                onOpenInspection = { id ->
                    navController.navigate(Routes.detail(id))
                }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument(Routes.ARG_INSPECTION_ID) { type = NavType.StringType }
            )
        ) { entry ->
            val inspectionId =
                entry.arguments?.getString(Routes.ARG_INSPECTION_ID).orEmpty()

            InspectionDetailScreen(
                inspectionId = inspectionId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onCaptureForItem = { checklistItemId, label ->
                    navController.navigate(
                        Routes.capture(inspectionId, checklistItemId, label)
                    )
                }
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(
                navArgument(Routes.ARG_INSPECTION_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_CHECKLIST_ITEM_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_LABEL) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val inspectionId =
                entry.arguments?.getString(Routes.ARG_INSPECTION_ID).orEmpty()
            val checklistItemId =
                entry.arguments?.getString(Routes.ARG_CHECKLIST_ITEM_ID)
            val label =
                entry.arguments?.getString(Routes.ARG_LABEL)

            CameraCaptureScreen(
                checklistItemTitle = label,
                requiresPhoto = checklistItemId != null,
                onPhotoCaptured = { uri ->
                    viewModel.addEvidence(
                        inspectionId = inspectionId,
                        checklistItemId = checklistItemId,
                        type = "PHOTO",
                        localUri = uri.toString(),
                        note = null
                    )
                    navController.popBackStack()
                },
                onClose = { navController.popBackStack() }
            )
        }
    }
}