// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.extractMethod.newImpl

import com.intellij.codeInsight.Nullability
import com.intellij.ide.util.PropertiesComponent
import com.intellij.java.refactoring.JavaRefactoringBundle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.*
import com.intellij.psi.util.PsiEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.JavaRefactoringSettings
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.extractMethod.ExtractMethodDialog
import com.intellij.refactoring.extractMethod.ExtractMethodHandler
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodHelper.guessMethodName
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodHelper.replaceWithMethod
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline.findAllOptionsToExtract
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline.selectOption
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline.withFilteredAnnotations
import com.intellij.refactoring.extractMethod.newImpl.inplace.*
import com.intellij.refactoring.extractMethod.newImpl.parameterObject.ResultObjectExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.refactoring.util.ConflictsUtil
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import com.intellij.refactoring.extractMethod.newImpl.inplace.InplaceExtractMethodCollector as IEMC

data class ExtractedElements(val callElements: List<PsiElement>, val method: PsiMethod)

class MethodExtractor {

  fun doExtract(file: PsiFile, range: TextRange) {
    if (!CommonRefactoringUtil.checkReadOnlyStatus(file.project, file)) return
    val coroutineScope = ExtractMethodService.getInstance(file.project).scope

    val editor = PsiEditorUtil.findEditor(file) ?: return
    val activeExtractor = InplaceMethodExtractor.getActiveExtractor(editor)
    if (activeExtractor != null) {
      activeExtractor.restartInDialog()
      return
    }

    coroutineScope.launch {
      doExtract(editor, file, range)
    }
  }

  private suspend fun doExtract(editor: Editor, file: PsiFile, range: TextRange){

      val elements = readAction { ExtractSelector().suggestElementsToExtract(file, range) }
      if (elements.isEmpty()) {
        InplaceExtractUtils.showExtractErrorHint(editor, RefactoringBundle.message("selected.block.should.represent.a.set.of.statements.or.an.expression"))
        return
      }

      val analyzer = readAction { CodeFragmentAnalyzer.createAnalyzer(elements) }
      if (analyzer == null) {
        InplaceExtractUtils.showExtractErrorHint(editor, JavaRefactoringBundle.message("extract.method.control.flow.analysis.failed"))
        return
      }

      val outputVariables = readAction { analyzer.findOutputVariables().sortedBy { variable -> variable.textRange.startOffset } }
      if (outputVariables.size > 1) {
        ResultObjectExtractor.run(editor, outputVariables, elements)
        return
      }

      val prepareStart = System.currentTimeMillis()
      val descriptorsForAllTargetPlaces = prepareDescriptorsForAllTargetPlaces(file.project, editor, elements)
      if (descriptorsForAllTargetPlaces.isEmpty()) return
      val preparePlacesTime = System.currentTimeMillis() - prepareStart

      val options = withContext(Dispatchers.EDT) {
        selectOption(editor, descriptorsForAllTargetPlaces)
      }
      if (EditorSettingsExternalizable.getInstance().isVariableInplaceRenameEnabled) {
        val templateStart = System.currentTimeMillis()
        runInplaceExtract(editor, range, options)
        val prepareTemplateTime = System.currentTimeMillis() - templateStart
        reportPerformanceStatistics(preparePlacesTime, prepareTemplateTime, descriptorsForAllTargetPlaces.size)
      }
      else {
        withContext(Dispatchers.EDT) {
          extractInDialog(options.targetClass, options.elements, "", JavaRefactoringSettings.getInstance().EXTRACT_STATIC_METHOD)
        }
      }
  }

  private fun reportPerformanceStatistics(preparePlacesMs: Long, prepareTemplateMs: Long, numberOfTargetPlaces: Int){
    IEMC.templateShown.log(
      IEMC.prepareTargetPlacesMs.with(preparePlacesMs),
      IEMC.numberOfTargetPlaces.with(numberOfTargetPlaces),
      IEMC.prepareTemplateMs.with(prepareTemplateMs),
      IEMC.prepareTotalMs.with(preparePlacesMs + prepareTemplateMs)
    )
  }

  private suspend fun prepareDescriptorsForAllTargetPlaces(project: Project, editor: Editor, elements: List<PsiElement>): List<ExtractOptions> {
    val message = JavaRefactoringBundle.message("dialog.title.analyze.code.fragment.to.extract")
    return withBackgroundProgress(project, message, true) {
      try {
        readAction { findAllOptionsToExtract(elements) }
      } catch (exception: ExtractException) {
        InplaceExtractUtils.showExtractErrorHint(editor, exception)
        emptyList()
      }
    }
  }

  @Deprecated("")
  fun prepareDescriptorsForAllTargetPlaces(editor: Editor, file: PsiFile, range: TextRange): List<ExtractOptions> {
    try {
      if (!CommonRefactoringUtil.checkReadOnlyStatus(file.project, file)) return emptyList()
      val elements = ExtractSelector().suggestElementsToExtract(file, range)
      if (elements.isEmpty()) {
        throw ExtractException(RefactoringBundle.message("selected.block.should.represent.a.set.of.statements.or.an.expression"), file)
      }
      return computeWithAnalyzeProgress<List<ExtractOptions>, ExtractException>(file.project) {
        findAllOptionsToExtract(elements)
      }
    }
    catch (exception: ExtractException) {
      InplaceExtractUtils.showExtractErrorHint(editor, exception)
      return emptyList()
    }
  }

  private fun <T, E: Exception> computeWithAnalyzeProgress(project: Project, throwableComputable: ThrowableComputable<T, E>): T {
    return ProgressManager.getInstance().run(object : Task.WithResult<T, E>(project,
      JavaRefactoringBundle.message("dialog.title.analyze.code.fragment.to.extract"), true) {
      override fun compute(indicator: ProgressIndicator): T {
        return ReadAction.compute(throwableComputable)
      }
    })
  }

  private suspend fun runInplaceExtract(editor: Editor, range: TextRange, options: ExtractOptions){
    val popupSettings = readAction { createInplaceSettingsPopup(options) }
    val guessedNames = readAction { suggestSafeMethodNames(options) }
    val methodName = guessedNames.first()
    val suggestedNames = guessedNames.takeIf { it.size > 1 }.orEmpty()
    val extractor = readAction { DuplicatesMethodExtractor.create(options.targetClass, options.elements, methodName, options.isStatic) }
    val inplaceExtractor = readAction { InplaceMethodExtractor(editor, range, popupSettings, extractor) }
    inplaceExtractor.extractAndRunTemplate(suggestedNames)
  }

  private fun suggestSafeMethodNames(options: ExtractOptions): List<String> {
    val unsafeNames = guessMethodName(options)
    val method = createMethodSignature(options)
    fun hasConflicts(name: String): Boolean {
      method.name = name
      val conflicts = MultiMap<PsiElement, String>()
      ConflictsUtil.checkMethodConflicts(options.targetClass, null, method, conflicts)
      return ! conflicts.isEmpty
    }
    val safeNames = unsafeNames.filterNot { name -> hasConflicts(name) }
    if (safeNames.isNotEmpty()) return safeNames

    val baseName = unsafeNames.firstOrNull() ?: "extracted"
    val generatedNames = sequenceOf(baseName) + generateSequence(1) { seed -> seed + 1 }.map { number -> "$baseName$number" }
    return generatedNames.filterNot { name -> hasConflicts(name) }.take(1).toList()
  }

  private fun createInplaceSettingsPopup(options: ExtractOptions): ExtractMethodPopupProvider {
    val analyzer = CodeFragmentAnalyzer(options.elements)
    val optionsWithStatic = ExtractMethodPipeline.withForcedStatic(analyzer, options)
    val makeStaticAndPassFields = optionsWithStatic?.inputParameters?.size != options.inputParameters.size
    val showStatic = !options.isStatic && optionsWithStatic != null
    val defaultStatic = with (JavaRefactoringSettings.getInstance()) {
      if (makeStaticAndPassFields) EXTRACT_STATIC_METHOD_AND_PASS_FIELDS else EXTRACT_STATIC_METHOD
    }
    val hasAnnotation = options.dataOutput.nullability != Nullability.UNKNOWN && options.dataOutput.type !is PsiPrimitiveType
    val annotationAvailable = ExtractMethodHelper.isNullabilityAvailable(options)
    return ExtractMethodPopupProvider(
      annotateDefault = if (hasAnnotation && annotationAvailable) needsNullabilityAnnotations(options.project) else null,
      makeStaticDefault = if (showStatic) defaultStatic else null,
      staticPassFields = makeStaticAndPassFields
    )
  }

  fun executeRefactoringCommand(project: Project, command: () -> Unit) {
    CommandProcessor.getInstance().executeCommand(project, command, ExtractMethodHandler.getRefactoringName(), null)
  }

  fun extractMethod(extractOptions: ExtractOptions): ExtractedElements {
    val preparedElements = prepareRefactoringElements(extractOptions)
    return WriteAction.compute<ExtractedElements, Throwable> {
      replaceWithMethod(extractOptions.targetClass, extractOptions.elements, preparedElements)
    }
  }

  fun doTestExtract(
    doRefactor: Boolean,
    editor: Editor,
    isConstructor: Boolean?,
    isStatic: Boolean?,
    returnType: PsiType?,
    newNameOfFirstParam: String?,
    targetClass: PsiClass?,
    @PsiModifier.ModifierConstant visibility: String?,
    vararg disabledParameters: Int
  ): Boolean {
    val project = editor.project ?: return false
    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return false
    val range = ExtractMethodHelper.findEditorSelection(editor) ?: return false
    val elements = ExtractSelector().suggestElementsToExtract(file, range)
    if (elements.isEmpty()) throw ExtractException("Nothing to extract", file)
    val analyzer = CodeFragmentAnalyzer(elements)
    val allOptionsToExtract = findAllOptionsToExtract(elements)
    var options = allOptionsToExtract.takeIf { targetClass != null }?.find { option -> option.targetClass == targetClass }
                  ?: allOptionsToExtract.find { option -> option.targetClass !is PsiAnonymousClass }
                  ?: allOptionsToExtract.first()
    options = options.copy(methodName = "newMethod")
    if (isConstructor != options.isConstructor){
      options = ExtractMethodPipeline.asConstructor(analyzer, options) ?: throw ExtractException("Fail", elements.first())
    }
    if (! options.isStatic && isStatic == true) {
      options = ExtractMethodPipeline.withForcedStatic(analyzer, options) ?: throw ExtractException("Fail", elements.first())
    }
    if (newNameOfFirstParam != null) {
      options = options.copy(
        inputParameters = listOf(options.inputParameters.first().copy(name = newNameOfFirstParam)) + options.inputParameters.drop(1)
      )
    }
    if (returnType != null) {
      options = options.copy(dataOutput = options.dataOutput.withType(returnType))
    }
    if (disabledParameters.isNotEmpty()) {
      options = options.copy(
        disabledParameters = options.inputParameters.filterIndexed { index, _ -> index in disabledParameters },
        inputParameters = options.inputParameters.filterIndexed { index, _ -> index !in disabledParameters }
      )
    }
    if (doRefactor) {
      extractMethod(options)
    }
    return true
  }

  fun prepareRefactoringElements(extractOptions: ExtractOptions): ExtractedElements {
    val dependencies = withFilteredAnnotations(extractOptions)
    val factory = PsiElementFactory.getInstance(dependencies.project)
    val codeBlock = BodyBuilder(factory)
      .build(
        dependencies.elements,
        dependencies.flowOutput,
        dependencies.dataOutput,
        dependencies.inputParameters,
        dependencies.disabledParameters,
        dependencies.requiredVariablesInside
      )
    val method = createMethodSignature(dependencies)
    method.body?.replace(codeBlock)

    if (needsNullabilityAnnotations(dependencies.project) && ExtractMethodHelper.isNullabilityAvailable(dependencies)) {
      updateMethodAnnotations(method, dependencies.inputParameters)
    }

    val context = PsiTreeUtil.getContextOfType(dependencies.elements.first(), PsiMember::class.java)
    val callElements = if (context !is PsiClass) {
      CallBuilder(dependencies.elements.first()).createCall(method, dependencies)
    } else {
      emptyList()
    }
    return ExtractedElements(callElements, method)
  }

  private fun createMethodSignature(dependencies: ExtractOptions): PsiMethod {
    return SignatureBuilder(dependencies.project)
      .build(
        dependencies.targetClass,
        dependencies.elements,
        dependencies.isStatic,
        dependencies.visibility,
        dependencies.typeParameters,
        dependencies.dataOutput.type.takeIf { !dependencies.isConstructor },
        dependencies.methodName,
        dependencies.inputParameters,
        dependencies.dataOutput.annotations,
        dependencies.thrownExceptions
      )
  }

  private fun needsNullabilityAnnotations(project: Project): Boolean {
    return PropertiesComponent.getInstance(project).getBoolean(ExtractMethodDialog.EXTRACT_METHOD_GENERATE_ANNOTATIONS, true)
  }

  companion object {
    private val LOG = Logger.getInstance(MethodExtractor::class.java)

    @NonNls const val refactoringId: String = "refactoring.extract.method"

    internal fun sendRefactoringDoneEvent(extractedMethod: PsiMethod) {
      LOG.debug("Extract method finished")
      val data = RefactoringEventData()
      data.addElement(extractedMethod)
      extractedMethod.project.messageBus.syncPublisher(RefactoringEventListener.REFACTORING_EVENT_TOPIC)
        .refactoringDone(refactoringId, data)
    }

    internal fun sendRefactoringStartedEvent(elements: Array<PsiElement>) {
      LOG.debug("Extract method started")
      val project = elements.firstOrNull()?.project ?: return
      val data = RefactoringEventData()
      data.addElements(elements)
      val publisher = project.messageBus.syncPublisher(RefactoringEventListener.REFACTORING_EVENT_TOPIC)
      publisher.refactoringStarted(refactoringId, data)
    }
  }
}