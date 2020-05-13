/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.parents
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedReceiverParameterDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeBuilder
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.isMultiPlatform
import org.jetbrains.kotlin.platform.js.isJs
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.replace
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlinx.stm.compiler.*
import org.jetbrains.kotlinx.stm.compiler.extensions.FunctionTransformMap
import org.jetbrains.kotlinx.stm.compiler.extensions.StmResolveExtension

// Is creating synthetic origin is a good idea or not?
object STM_PLUGIN_ORIGIN : IrDeclarationOriginImpl("STM")

val BackendContext.externalSymbols: ReferenceSymbolTable get() = ir.symbols.externalSymbolTable

internal fun BackendContext.createTypeTranslator(moduleDescriptor: ModuleDescriptor): TypeTranslator =
    TypeTranslator(externalSymbols, irBuiltIns.languageVersionSettings, moduleDescriptor.builtIns).apply {
        constantValueGenerator = ConstantValueGenerator(moduleDescriptor, symbolTable = externalSymbols)
        constantValueGenerator.typeTranslator = this
    }

private fun isStmContextType(type: IrType?) = type?.classOrNull?.isClassWithFqName(STM_CONTEXT_CLASS.toUnsafe())
    ?: false

internal fun fetchStmContextOrNull(functionStack: MutableList<IrFunction>): IrGetValue? {
    val ctx = functionStack.firstNotNullResult {
        when {
            isStmContextType(it.dispatchReceiverParameter?.type) -> {
                it.dispatchReceiverParameter!!
            }
            isStmContextType(it.extensionReceiverParameter?.type) -> {
                it.extensionReceiverParameter!!
            }
            isStmContextType(it.valueParameters.lastOrNull()?.type) -> {
                it.valueParameters.last()
            }
            else -> null
        }
    }
        ?: return null

    return IrGetValueImpl(ctx.startOffset, ctx.endOffset, ctx.type, ctx.symbol)
}

class STMGenerator(override val compilerContext: IrPluginContext) : IrBuilderExtension() {

    fun generateSTMField(irClass: IrClass, field: IrField, initMethod: IrFunctionSymbol) =
        irClass.initField(field) {
            irCall(initMethod, field.type)
        }

    fun createReceiverParam(
        type: IrType,
        paramDesc: ReceiverParameterDescriptor,
        name: String,
        index: Int
    ): IrValueParameter {
        val paramSymbol = IrValueParameterSymbolImpl(paramDesc)
        val param = IrValueParameterImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            symbol = paramSymbol,
            name = Name.identifier(name),
            index = index,
            type = type,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false
        )

        return param
    }

    fun wrapFunctionIntoTransaction(
        irClass: IrClass,
        irFunction: IrSimpleFunction,
        stmField: IrField,
        runAtomically: IrFunctionSymbol,
        stmContextType: IrType
    ) {
        val functionDescriptor = irFunction.descriptor

        irClass.contributeFunction(functionDescriptor) {

            val ctxReceiverDescriptor = WrappedReceiverParameterDescriptor()
            val ctxReceiver = createReceiverParam(stmContextType, ctxReceiverDescriptor, "ctx", index = 0)
            ctxReceiverDescriptor.bind(ctxReceiver)

            val funReturnType = irFunction.returnType

            val lambdaDescriptor = WrappedSimpleFunctionDescriptor()
            val irLambda = IrFunctionImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = STM_PLUGIN_ORIGIN,
                symbol = IrSimpleFunctionSymbolImpl(lambdaDescriptor),
                name = "${irFunction.name}_atomicLambda".synthesizedName,
                visibility = Visibilities.LOCAL,
                modality = functionDescriptor.modality,
                returnType = funReturnType,
                isInline = irFunction.isInline,
                isExternal = irFunction.isExternal,
                isTailrec = irFunction.isTailrec,
                isSuspend = irFunction.isSuspend,
                isExpect = irFunction.isExpect,
                isFakeOverride = false,
                isOperator = false
            ).apply {
                lambdaDescriptor.bind(this)
                parent = irFunction
                extensionReceiverParameter = ctxReceiver
                body = DeclarationIrBuilder(compilerContext, this.symbol, irFunction.startOffset, irFunction.endOffset).irBlockBody(
                    this.startOffset,
                    this.endOffset
                ) {
                    irFunction.body?.deepCopyWithSymbols(initialParent = this@apply)?.statements?.forEach { st ->
                        when (st) {
                            is IrReturn -> +irReturn(st.value)
                            else -> +st
                        }
                    }
                }
                patchDeclarationParents(irFunction)
            }

            val lambdaType = runAtomically.descriptor.valueParameters[1].type.replace(
                listOf(
                    TypeProjectionImpl(
                        Variance.INVARIANT,
                        runAtomically.descriptor.valueParameters[0].type.makeNotNullable()
                    ),
                    TypeProjectionImpl(
                        Variance.INVARIANT,
                        funReturnType.toKotlinType()
                    )
                )
            ).toIrType()

            val lambdaExpression = IrFunctionExpressionImpl(
                irLambda.startOffset, irLambda.endOffset,
                lambdaType,
                irLambda,
                IrStatementOrigin.LAMBDA
            )

            val stmFieldExpr = irGetField(irGet(irFunction.dispatchReceiverParameter!!), stmField)

            val functionStack = irFunction.parents.mapNotNull { it as? IrFunction }.toMutableList()

            /* Note: this is needed for the case when we are transforming fake overrides.
             Fake overrides must now be also a real defined functions,
             example:
             override fun toString(): String {
                return runAtomically { super.toString() }
             }
             */
            it.origin = IrDeclarationOrigin.DEFINED

            +irReturn(
                irInvoke(
                    dispatchReceiver = stmFieldExpr,
                    callee = runAtomically,
                    args = *arrayOf(
                        fetchStmContextOrNull(functionStack)
                            ?: irNull(runAtomically.descriptor.valueParameters[0].type.toIrType()),
                        lambdaExpression
                    ),
                    typeHint = funReturnType
                ).apply {
                    putTypeArgument(index = 0, type = funReturnType)
                }
            )
        }
    }

    fun addDelegateField(
        irClass: IrClass,
        propertyName: Name,
        backingField: IrField,
        stmField: IrField,
        wrap: IrFunctionSymbol,
        universalDelegateClassSymbol: IrClassSymbol
    ): IrField {

        val delegateType = IrSimpleTypeBuilder().run {
            classifier = universalDelegateClassSymbol
            hasQuestionMark = false
            val type = backingField.type

            arguments = listOf(
                makeTypeProjection(type, Variance.INVARIANT)
            )
            buildSimpleType()
        }

        val delegateField = irClass.addField {
            name = Name.identifier("${propertyName}${SHARABLE_NAME_SUFFIX}")
            type = delegateType
            visibility = Visibilities.PRIVATE
            origin = IrDeclarationOrigin.DELEGATED_MEMBER
            isFinal = true
            isStatic = false
            buildField()
        }

        irClass.initField(delegateField) {
            val stmFieldExpr = irGetField(irGet(irClass.thisReceiver!!), stmField)

            val initValue =
                backingField.initializer?.expression?.deepCopyWithSymbols(initialParent = delegateField) ?: irNull(backingField.type)

            irInvoke(
                dispatchReceiver = stmFieldExpr,
                callee = wrap,
                args = *arrayOf(initValue),
                typeHint = delegateField.type
            ).apply {
                putTypeArgument(index = 0, type = backingField.type)
            }
        }

        return delegateField
    }

    private fun IrClass.findMethodDescriptor(name: Name) = this.descriptor
        .findMethods(name)
        .firstOrNull()
        ?.safeAs<FunctionDescriptor>()

    fun addGetFunction(
        irClass: IrClass,
        propertyName: Name,
        delegate: IrField,
        stmField: IrField,
        getVar: IrFunctionSymbol
    ) {
        val getterFunDescriptor =
            irClass.findMethodDescriptor(StmResolveExtension.getterName(propertyName)) ?: return

        irClass.contributeFunction(getterFunDescriptor) { f ->
            val stmFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), stmField)

            val stmContextParam = f.valueParameters[0]

            val delegateFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), delegate)

            f.dispatchReceiverParameter!!.parent = f

            +irReturn(
                irInvoke(
                    dispatchReceiver = stmFieldExpr,
                    callee = getVar,
                    args = *arrayOf(irGet(stmContextParam), delegateFieldExpr),
                    typeHint = getterFunDescriptor.returnType?.toIrType()
                ).apply {
                    putTypeArgument(
                        index = 0,
                        type = delegateFieldExpr.type
                            .safeAs<IrSimpleType>()
                            ?.arguments
                            ?.first()
                            ?.typeOrNull
                            ?: throw StmLoweringException("Expected delegate field for property $propertyName to be defined and have a type")
                    )
                }
            )
        }
    }

    fun addSetFunction(
        irClass: IrClass,
        propertyName: Name,
        delegate: IrField,
        stmField: IrField,
        setVar: IrFunctionSymbol
    ) {
        val setterFunDescriptor =
            irClass.findMethodDescriptor(StmResolveExtension.setterName(propertyName)) ?: return

        irClass.contributeFunction(setterFunDescriptor) { f ->
            val stmFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), stmField)

            val stmContextParam = f.valueParameters[0]
            val newValueParameter = f.valueParameters[1]

            val delegateFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), delegate)

            +irInvoke(
                dispatchReceiver = stmFieldExpr,
                callee = setVar,
                args = *arrayOf(irGet(stmContextParam), delegateFieldExpr, irGet(newValueParameter)),
                typeHint = context.irBuiltIns.unitType
            ).apply {
                putTypeArgument(index = 0, type = newValueParameter.type)
            }
        }
    }

    fun addDelegateAndAccessorFunctions(
        irClass: IrClass,
        propertyName: Name,
        backingField: IrField,
        stmField: IrField,
        wrap: IrFunctionSymbol,
        universalDelegateClassSymbol: IrClassSymbol,
        getVar: IrFunctionSymbol,
        setVar: IrFunctionSymbol,
        oldDeclaration: IrDeclaration
    ) {
        val delegate =
            addDelegateField(irClass, propertyName, backingField, stmField, wrap, universalDelegateClassSymbol)

        addGetFunction(irClass, propertyName, delegate, stmField, getVar)
        addSetFunction(irClass, propertyName, delegate, stmField, setVar)

        irClass.declarations -= oldDeclaration
    }
}

private fun ClassDescriptor.checkPublishMethodResult(type: KotlinType): Boolean =
    KotlinBuiltIns.isInt(type)

private fun ClassDescriptor.checkPublishMethodParameters(parameters: List<ValueParameterDescriptor>): Boolean =
    parameters.size == 0

class StmLoweringException(override val message: String) : Exception()

open class StmIrGenerator {

    companion object {

        private fun findSTMClassDescriptorOrNull(
            module: ModuleDescriptor,
            className: Name
        ): ClassDescriptor? =
            module.findClassAcrossModuleDependencies(
                ClassId(
                    STM_PACKAGE,
                    className
                )
            )

        private fun findSTMClassDescriptorOrThrow(
            module: ModuleDescriptor,
            className: Name
        ) = findSTMClassDescriptorOrNull(module, className)
            ?: throw StmLoweringException("Couldn't find $className runtime class in dependencies of module ${module.name}")

        private fun findSTMContextTypeOrThrow(
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable
        ): IrType =
            findSTMClassDescriptorOrThrow(module, STM_CONTEXT)
                .let(symbolTable::referenceClass)
                .defaultType

        private fun findMethodDescriptorOrNull(
            classDescriptor: ClassDescriptor,
            methodName: Name
        ): SimpleFunctionDescriptor? =
            classDescriptor.findMethods(methodName).firstOrNull()

        private fun findMethodDescriptorOrThrow(
            module: ModuleDescriptor,
            classDescriptor: ClassDescriptor,
            methodName: Name
        ) = findMethodDescriptorOrNull(classDescriptor, methodName) ?: throw StmLoweringException(
            "Couldn't find ${classDescriptor.name}.$methodName(...) runtime method in dependencies of module ${module.name}"
        )

        private fun findSTMMethodDescriptorOrNull(
            module: ModuleDescriptor,
            className: Name,
            methodName: Name
        ): SimpleFunctionDescriptor? =
            findSTMClassDescriptorOrNull(module, className)?.let { classDescriptor ->
                findMethodDescriptorOrNull(
                    classDescriptor,
                    methodName
                )
            }

        private fun findSTMMethodDescriptorOrThrow(
            module: ModuleDescriptor,
            className: Name,
            methodName: Name
        ): SimpleFunctionDescriptor = findMethodDescriptorOrThrow(
            module,
            findSTMClassDescriptorOrThrow(module, className),
            methodName
        )

        private fun findSTMMethodIrOrNull(
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable,
            className: Name,
            methodName: Name
        ): IrFunctionSymbol? =
            findSTMMethodDescriptorOrNull(module, className, methodName)?.let(symbolTable::referenceSimpleFunction)


        private fun findSTMMethodIrOrThrow(
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable,
            className: Name,
            methodName: Name
        ): IrFunctionSymbol =
            symbolTable.referenceSimpleFunction(findSTMMethodDescriptorOrThrow(module, className, methodName))

        private fun getSTMField(irClass: IrClass, symbolTable: ReferenceSymbolTable): IrField {
            val stmClassSymbol = findSTMClassDescriptorOrThrow(irClass.module, STM_INTERFACE)
                .let(symbolTable::referenceClass)

            val stmType = IrSimpleTypeBuilder().run {
                classifier = stmClassSymbol
                hasQuestionMark = false
                buildSimpleType()
            }

            return irClass.addField {
                name = Name.identifier(STM_FIELD_NAME)
                type = stmType
                visibility = Visibilities.PRIVATE
                origin = IrDeclarationOrigin.DELEGATED_MEMBER
                isFinal = true
                isStatic = false
                buildField()
            }
        }

        private fun getSTMSearchFunction(
            module: ModuleDescriptor,
            compilerContext: IrPluginContext
        ): IrFunctionSymbol {
            val methodBaseName = when {
                module.platform.isJs() -> SEARCH_JS_STM_METHOD
                module.platform.isJvm() -> SEARCH_JAVA_STM_METHOD
                module.platform.isMultiPlatform() -> error("Unexpected platform in IR code: Multiplatform")
                module.platform.isCommon() -> error("Unexpected platform in IR code: Common")
                else -> SEARCH_NATIVE_STM_METHOD
            }
            val methodDefaultName = Name.identifier("$methodBaseName$DEFAULT_SUFFIX")

            return compilerContext.referenceFunctions(STM_PACKAGE.child(methodBaseName)).singleOrNull()
                ?: compilerContext.referenceFunctions(STM_PACKAGE.child(methodDefaultName)).singleOrNull()
                ?: throw StmLoweringException("Expected $methodDefaultName to be visible in module ${module.name}")
        }

        private fun getRunAtomicallyFunction(
            module: ModuleDescriptor,
            compilerContext: IrPluginContext
        ): IrFunctionSymbol =
            compilerContext.referenceFunctions(STM_PACKAGE.child(RUN_ATOMICALLY_GLOBAL_FUNCTION))
                .find { it.descriptor.valueParameters.size == 2 }
                ?: throw StmLoweringException("Expected $RUN_ATOMICALLY_GLOBAL_FUNCTION to be visible in module ${module.name}")

        private fun getSTMWrapMethod(module: ModuleDescriptor, symbolTable: ReferenceSymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, WRAP_METHOD)

        private fun getSTMGetvarMethod(module: ModuleDescriptor, symbolTable: ReferenceSymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, GET_VAR_METHOD)

        private fun getSTMSetvarMethod(module: ModuleDescriptor, symbolTable: ReferenceSymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, SET_VAR_METHOD)

        private fun getRunAtomicallyFun(module: ModuleDescriptor, symbolTable: ReferenceSymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, RUN_ATOMICALLY_METHOD)

        private fun getSTMContextType(
            compilerContext: IrPluginContext,
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable
        ): IrType =
            compilerContext.typeTranslator.translateType(
                findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, GET_CONTEXT).descriptor.returnType!!
            )

        fun patchSharedClass(
            irClass: IrClass,
            context: IrPluginContext,
            symbolTable: ReferenceSymbolTable
        ) {
            val generator = STMGenerator(context)

            val stmField = getSTMField(irClass, symbolTable)
            val stmSearch = getSTMSearchFunction(irClass.module, context)

            generator.generateSTMField(irClass, stmField, stmSearch)

            val universalDelegateClassSymbol = findSTMClassDescriptorOrThrow(irClass.module, UNIVERSAL_DELEGATE)
                .let(symbolTable::referenceClass)
            val stmWrap = getSTMWrapMethod(irClass.module, symbolTable)
            val getVar = getSTMGetvarMethod(irClass.module, symbolTable)
            val setVar = getSTMSetvarMethod(irClass.module, symbolTable)
            val stmContextType = getSTMContextType(context, irClass.module, symbolTable)

            val runAtomically = getRunAtomicallyFun(irClass.module, symbolTable)

            irClass.functions.forEach { f ->
                generator.wrapFunctionIntoTransaction(irClass, f, stmField, runAtomically, stmContextType)
            }

            val oldDeclarations = mutableListOf<IrDeclaration>()
            irClass.declarations.forEach { oldDeclarations.add(it) }

            oldDeclarations.forEach { p ->
                when (p) {
                    is IrProperty -> {
                        val backingField = p.backingField
                        val pName = p.name

                        if (backingField != null && !pName.isSTMFieldName() && !pName.isSharable())
                            generator.addDelegateAndAccessorFunctions(
                                irClass,
                                pName,
                                backingField,
                                stmField,
                                stmWrap,
                                universalDelegateClassSymbol,
                                getVar,
                                setVar,
                                p
                            )
                    }
                    is IrField -> {
                        val pName = p.name

                        if (!pName.isSTMFieldName() && !pName.isSharable())
                            generator.addDelegateAndAccessorFunctions(
                                irClass,
                                pName,
                                p,
                                stmField,
                                stmWrap,
                                universalDelegateClassSymbol,
                                getVar,
                                setVar,
                                p
                            )
                    }
                }
            }
        }

        private fun getSyntheticAccessorForSharedClass(
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable,
            classDescriptor: ClassDescriptor,
            accessorName: Name
        ): IrFunctionSymbol =
            symbolTable.referenceSimpleFunction(
                findMethodDescriptorOrThrow(
                    module,
                    classDescriptor,
                    accessorName
                )
            )

        private fun getSyntheticGetterForSharedClass(
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable,
            classDescriptor: ClassDescriptor,
            varName: Name
        ): IrFunctionSymbol =
            getSyntheticAccessorForSharedClass(module, symbolTable, classDescriptor, StmResolveExtension.getterName(varName))


        private fun getSyntheticSetterForSharedClass(
            module: ModuleDescriptor,
            symbolTable: ReferenceSymbolTable,
            classDescriptor: ClassDescriptor,
            varName: Name
        ): IrFunctionSymbol =
            getSyntheticAccessorForSharedClass(module, symbolTable, classDescriptor, StmResolveExtension.setterName(varName))

        private fun callFunction(
            f: IrFunctionSymbol,
            oldCall: IrCall,
            dispatchReceiver: IrExpression?,
            extensionReceiver: IrExpression?,
            vararg args: IrExpression?
        ): IrCall {
            val newCall = IrCallImpl(
                oldCall.startOffset,
                oldCall.endOffset,
                oldCall.type,
                f,
                oldCall.origin,
                oldCall.superQualifierSymbol
            )

            args.forEachIndexed { index, irExpression -> newCall.putValueArgument(index, irExpression) }

            newCall.dispatchReceiver = dispatchReceiver
            newCall.extensionReceiver = extensionReceiver

            return newCall
        }

        fun patchFunction(
            oldFunction: IrFunction,
            symbolTable: ReferenceSymbolTable,
            argumentMap: HashMap<IrValueSymbol, IrValueParameter>
        ): IrFunction {
            val oldDescriptor = oldFunction.descriptor

            val newDescriptor = WrappedSimpleFunctionDescriptor()
            val newFunction = IrFunctionImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = STM_PLUGIN_ORIGIN,
                symbol = IrSimpleFunctionSymbolImpl(newDescriptor),
                name = oldFunction.name,
                visibility = Visibilities.LOCAL,
                modality = oldDescriptor.modality,
                returnType = oldFunction.returnType,
                isInline = oldFunction.isInline,
                isExternal = oldFunction.isExternal,
                isTailrec = oldFunction.safeAs<IrSimpleFunction>()?.isTailrec ?: false,
                isSuspend = oldFunction.isSuspend,
                isExpect = oldFunction.isExpect,
                isFakeOverride = false,
                isOperator = false
            ).apply {
                newDescriptor.bind(this)
                extensionReceiverParameter = oldFunction.extensionReceiverParameter
                dispatchReceiverParameter = oldFunction.dispatchReceiverParameter
                body = oldFunction.body?.deepCopyWithSymbols(initialParent = this)
                valueParameters = oldFunction.valueParameters.map { it.copyTo(this) } + buildValueParameter {
                    type = findSTMContextTypeOrThrow(oldFunction.module, symbolTable)
                    index = oldFunction.valueParameters.size
                    name = "ctx".synthesizedName
                    parent = this@apply
                }
                patchDeclarationParents(oldFunction.parent)
            }

            oldFunction.valueParameters.forEachIndexed { i, oldArg ->
                argumentMap[oldArg.symbol] = newFunction.valueParameters[i]
            }

            return newFunction
        }

        private fun fetchStmContext(functionStack: MutableList<IrFunction>, currentFunctionName: Name): IrGetValue =
            fetchStmContextOrNull(functionStack)
                ?: throw StmLoweringException("Call of function $currentFunctionName requires $STM_CONTEXT_CLASS to be present in scope")

        fun patchPropertyAccess(
            irCall: IrCall,
            accessorDescriptor: PropertyAccessorDescriptor,
            functionStack: MutableList<IrFunction>,
            symbolTable: ReferenceSymbolTable,
            compilerContext: IrPluginContext
        ): IrCall {
            val propertyName = accessorDescriptor.correspondingProperty.name

            if (propertyName.asString().startsWith(STM_FIELD_NAME))
                return irCall

            val dispatchReceiver = irCall.dispatchReceiver?.deepCopyWithVariables()
            val extensionReceiver = irCall.extensionReceiver?.deepCopyWithVariables()
            val classDescriptor = dispatchReceiver?.type?.classOrNull?.descriptor
                ?: extensionReceiver?.type?.classOrNull?.descriptor
                ?: throw StmLoweringException("Unexpected call of setter for an unknown class (setter's descriptor could not be found: $irCall)")

            val contextValue = fetchStmContextOrNull(functionStack)

            fun nullCtx(accessor: IrFunctionSymbol): IrExpression =
                IrConstImpl.constNull(
                    irCall.startOffset,
                    irCall.endOffset,
                    with(STMGenerator(compilerContext)) { accessor.descriptor.valueParameters[0].type.toIrType() }
                )

            return when {
                accessorDescriptor is PropertyGetterDescriptor -> {
                    val getter = getSyntheticGetterForSharedClass(accessorDescriptor.module, symbolTable, classDescriptor, propertyName)

                    callFunction(
                        f = getter,
                        oldCall = irCall,
                        dispatchReceiver = dispatchReceiver,
                        extensionReceiver = extensionReceiver,
                        args = *arrayOf(contextValue ?: nullCtx(getter))
                    )
                }
                else -> /* PropertySetterDescriptor */ {
                    val setter = getSyntheticSetterForSharedClass(accessorDescriptor.module, symbolTable, classDescriptor, propertyName)
                    val newValue = irCall.getValueArgument(0)?.deepCopyWithVariables()

                    callFunction(
                        f = setter,
                        oldCall = irCall,
                        dispatchReceiver = dispatchReceiver,
                        extensionReceiver = extensionReceiver,
                        args = *arrayOf(contextValue ?: nullCtx(setter), newValue)
                    )
                }
            }

        }

        fun patchAtomicFunctionCall(
            irCall: IrCall,
            irFunction: IrFunctionSymbol,
            functionStack: MutableList<IrFunction>,
            funTransformMap: FunctionTransformMap
        ): IrCall {
            val funName = irFunction.descriptor.name
            val contextValue = fetchStmContext(functionStack, currentFunctionName = funName)

            val newFunction = funTransformMap[irFunction]?.symbol
                ?: throw StmLoweringException("Function $funName expected to be mapped to a transformed function")

            val dispatchReceiver = irCall.dispatchReceiver?.deepCopyWithVariables()
            val extensionReceiver = irCall.extensionReceiver?.deepCopyWithVariables()
            val args = (0 until irCall.valueArgumentsCount)
                .map(irCall::getValueArgument)
                .map { it?.deepCopyWithSymbolsAndParent() }
                .toMutableList()

            args += contextValue

            return callFunction(
                f = newFunction,
                oldCall = irCall,
                dispatchReceiver = dispatchReceiver,
                extensionReceiver = extensionReceiver,
                args = *args.toTypedArray()
            )
        }

        fun patchGetUpdatedValue(expression: IrGetValue, newValue: IrValueParameter) = IrGetValueImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            newValue.symbol,
            expression.origin
        )


        private class ParentSearcherVisitor : IrElementVisitorVoid {
            var result: IrDeclarationParent? = null
            override fun visitDeclaration(declaration: IrDeclaration) {
                result = declaration.parent
            }

            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        }

        private fun IrElement.findParent(): IrDeclarationParent? = ParentSearcherVisitor().let {
            this.acceptVoid(it)
            it.result
        }

        private inline fun <reified T : IrElement> T.deepCopyWithSymbolsAndParent(): T =
            this.deepCopyWithSymbols(initialParent = this.findParent())

        fun patchRunAtomicallyCall(
            irCall: IrCall,
            irFunction: IrFunctionSymbol,
            context: IrPluginContext
        ): IrCall {
            val newFunction = getRunAtomicallyFunction(irFunction.descriptor.module, context)

            val block = irCall.getValueArgument(0)?.deepCopyWithSymbolsAndParent()

            val stmType = context.typeTranslator.translateType(newFunction.descriptor.valueParameters[0].type)
            val stmSearch = getSTMSearchFunction(irFunction.descriptor.module, context)
            val stm = with(DeclarationIrBuilder(context, irFunction, irCall.startOffset, irCall.endOffset)) {
                irCall(stmSearch, stmType)
            }

            val res = callFunction(
                f = newFunction,
                oldCall = irCall,
                dispatchReceiver = irCall.dispatchReceiver,
                extensionReceiver = irCall.extensionReceiver,
                args = *arrayOf(stm, block)
            )

            return res
        }
    }
}