// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.quickfix

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.Presentation
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtPsiFactory

class WrongLongSuffixFix(
    element: KtConstantExpression
) : KotlinPsiUpdateModCommandAction.ElementBased<KtConstantExpression, Unit>(element, Unit) {
    private val corrected = element.text.trimEnd('l') + 'L'

    override fun getPresentation(
        context: ActionContext,
        element: KtConstantExpression,
    ): Presentation {
        return Presentation.of(KotlinBundle.message("change.to.0", corrected))
    }

    override fun getFamilyName() = KotlinBundle.message("change.to.correct.long.suffix.l")

    override fun invoke(
        actionContext: ActionContext,
        element: KtConstantExpression,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.replace(KtPsiFactory(actionContext.project).createExpression(corrected))
    }
}
