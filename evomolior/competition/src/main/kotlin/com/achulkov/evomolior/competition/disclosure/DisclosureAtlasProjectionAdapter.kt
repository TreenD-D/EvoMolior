package com.achulkov.evomolior.competition.disclosure

import com.achulkov.evomolior.substrate.atlas.DisclosureProjection

/**
 * Restores the legacy progressive-disclosure consumer from the Atlas projection.
 */
object DisclosureAtlasProjectionAdapter {

    /** Returns the exact migrated stages, with a metadata-only fallback for old projections. */
    fun fromProjection(projection: DisclosureProjection): List<DisclosureStage> {
        if (projection.legacyStages.isNotEmpty()) {
            return projection.legacyStages.map { stage ->
                DisclosureStage(
                    stageIndex = stage.stageIndex,
                    title = stage.title,
                    constraintFramedContent = stage.constraintFramedContent,
                    goalFramedContent = stage.goalFramedContent,
                    unlockCondition = stage.unlockCondition,
                    isInitial = stage.isInitial
                )
            }
        }
        return projection.stages.map { stage ->
            DisclosureStage(
                stageIndex = stage.stageIndex,
                title = stage.title,
                constraintFramedContent = stage.title,
                goalFramedContent = stage.title,
                unlockCondition = "Atlas stage ${stage.stageId}",
                isInitial = stage.stageIndex == 0
            )
        }
    }
}
