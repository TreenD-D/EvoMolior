package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.repair.AggregationSemantics
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArcAgi2NotebookQualificationAuthorityTest {
    @Test
    fun `ARC authority rejects quantitative field metadata drift`() {
        val canonical = ArcAgi2NotebookQualificationAuthority.quantitativeToolObligation
        ArcAgi2NotebookQualificationAuthority.requireExact(canonical)
        val firstField = canonical.requiredFields.first()
        val drifted = canonical.copy(
            requiredFields = listOf(
                firstField.copy(
                    quantitySpec = firstField.quantitySpec.copy(
                        aggregation = AggregationSemantics.MAXIMUM
                    )
                )
            ) + canonical.requiredFields.drop(1)
        )

        assertFailsWith<IllegalArgumentException> {
            ArcAgi2NotebookQualificationAuthority.requireExact(drifted)
        }
    }

    @Test
    fun `ARC authority accepts only the canonical notebook output contract`() {
        val canonical =
            FinalOutputSpec(
                relativePath = ArcAgi2NotebookQualificationAuthority.NOTEBOOK_RELATIVE_PATH,
                mediaType = ArcAgi2NotebookQualificationAuthority.NOTEBOOK_MEDIA_TYPE,
                validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
            )

        ArcAgi2NotebookQualificationAuthority.requireExactNotebookOutputSpec(canonical)

        assertFailsWith<IllegalArgumentException> {
            ArcAgi2NotebookQualificationAuthority.requireExactNotebookOutputSpec(
                canonical.copy(relativePath = "other.ipynb"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArcAgi2NotebookQualificationAuthority.requireExactNotebookOutputSpec(
                canonical.copy(mediaType = "application/json"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArcAgi2NotebookQualificationAuthority.requireExactNotebookOutputSpec(
                canonical.copy(
                    validation = requireNotNull(canonical.validation).copy(
                        requiredJsonRootKeys = listOf("cells"),
                    ),
                ),
            )
        }
    }
}
