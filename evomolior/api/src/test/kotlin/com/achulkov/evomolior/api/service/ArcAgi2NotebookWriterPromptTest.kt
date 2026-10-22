package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.JupyterNotebookSourceContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcAgi2NotebookWriterPromptTest {
    @Test
    fun `ARC writer blueprint contains every authoritative notebook signal`() {
        val notebook = requireNotNull(
            ArcAgi2NotebookQualificationAuthority.notebookValidationContract
                .jupyterNotebookSource
        )
        val blueprint = ArcAgi2NotebookQualificationAuthority.notebookWriterBlueprint

        (notebook.requiredCodeSignals + notebook.requiredMarkdownSignals).forEach { signal ->
            assertTrue(
                blueprint.contains(signal),
                "ARC writer blueprint is missing required signal: $signal"
            )
        }
        assertTrue(blueprint.contains("inside code-cell source"))
        assertTrue(blueprint.contains("nbformat 4.5"))
    }

    @Test
    fun `exact ARC notebook prompt appends the copyable blueprint`() {
        val prompt = finalDeliverableWriterValidationRequirements(arcNotebookSpec())

        assertTrue(prompt?.contains("ARC NOTEBOOK IMPLEMENTATION CHECKLIST") == true)
        assertTrue(
            prompt?.contains(
                ArcAgi2NotebookQualificationAuthority.notebookWriterBlueprint
            ) == true
        )
    }

    @Test
    fun `generic output keeps the existing validation prompt unchanged`() {
        val genericSpec = FinalOutputSpec(
            relativePath = "final-answer.md",
            mediaType = "text/markdown",
            validation = FinalOutputValidationContract(
                requiredMarkdownSectionSignals = listOf("Evidence traceability")
            )
        )

        assertEquals(
            FinalOutputContractValidator.promptRequirements(genericSpec),
            finalDeliverableWriterValidationRequirements(genericSpec)
        )
        assertFalse(
            finalDeliverableWriterValidationRequirements(genericSpec)
                ?.contains("ARC NOTEBOOK") == true
        )
    }

    @Test
    fun `a non ARC notebook contract keeps the generic notebook path`() {
        val genericNotebookSpec = FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = FinalOutputValidationContract(
                requiredJsonRootKeys = listOf("cells"),
                jupyterNotebookSource = JupyterNotebookSourceContract(
                    minimumCodeCellCount = 1
                )
            )
        )

        assertFalse(ArcAgi2NotebookRecoveryFallback.supports(genericNotebookSpec))
        assertEquals(
            FinalOutputContractValidator.promptRequirements(genericNotebookSpec),
            finalDeliverableWriterValidationRequirements(genericNotebookSpec)
        )
    }

    private fun arcNotebookSpec(): FinalOutputSpec =
        FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract
        )
}
