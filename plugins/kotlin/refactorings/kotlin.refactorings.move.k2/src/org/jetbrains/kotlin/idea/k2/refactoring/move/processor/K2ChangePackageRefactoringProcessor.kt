// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.move.processor

import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2MoveRenameUsageInfo.Companion.unMarkNonUpdatableUsages

class K2ChangePackageRefactoringProcessor(private val descriptor: K2ChangePackageDescriptor) : BaseRefactoringProcessor(descriptor.project) {
    override fun getCommandName(): String = KotlinBundle.message(
        "text.change.file.package.to.0",
        descriptor.target.presentablePkgName()
    )

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = descriptor.usageViewDescriptor()

    override fun findUsages(): Array<UsageInfo> {
        return descriptor.files.flatMap {
            it.findUsages(descriptor.searchInComments, descriptor.searchForText, descriptor.target)
        }.toTypedArray()
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val usages = refUsages.get()
        val conflicts = ActionUtil.underModalProgress(
            descriptor.project,
            RefactoringBundle.message("detecting.possible.conflicts")
        ) {
            findAllMoveConflicts(
                descriptor.files,
                descriptor.target,
                usages.filterIsInstance<MoveRenameUsageInfo>()
            )
        }
        val toContinue = showConflicts(conflicts, usages)
        if (!toContinue) return false
        unMarkNonUpdatableUsages(descriptor.files)
        refUsages.set(K2MoveRenameUsageInfo.filterUpdatable(descriptor.files, usages).toTypedArray())
        return true
    }

    @OptIn(KaAllowAnalysisOnEdt::class)
    override fun performRefactoring(usages: Array<out UsageInfo>) = allowAnalysisOnEdt {
        val files = descriptor.files
        files.forEach { it.updatePackageDirective(descriptor.target) }
        val oldToNewMap = files.flatMap { it.allDeclarationsToUpdate }.associateWith { it }
        @Suppress("UNCHECKED_CAST")
        retargetUsagesAfterMove(usages.toList(), oldToNewMap as Map<PsiElement, PsiElement>)
    }

    override fun getBeforeData(): RefactoringEventData = RefactoringEventData().apply {
        addElements(descriptor.files)
    }
}