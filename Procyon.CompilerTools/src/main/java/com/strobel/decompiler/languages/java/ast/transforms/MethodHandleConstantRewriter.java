package com.strobel.decompiler.languages.java.ast.transforms;

import com.strobel.assembler.Collection;
import com.strobel.assembler.metadata.*;
import com.strobel.core.VerifyArgument;
import com.strobel.decompiler.DecompilerContext;
import com.strobel.decompiler.ast.Variable;
import com.strobel.decompiler.languages.TextLocation;
import com.strobel.decompiler.languages.java.ast.*;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

public class MethodHandleConstantRewriter extends AbstractHelperClassTransform {
    private final Map<MethodHandle, LcdMHHelperBuilder> helpers = new HashMap<>();

    public MethodHandleConstantRewriter(final DecompilerContext context) {
        super(context);
    }

    @Override
    public void run(final AstNode compilationUnit) {
        this.helpers.clear();
        super.run(compilationUnit);
    }

    @Override
    public Void visitBytecodeConstant(final BytecodeConstant node, final Void data) {
        if (node instanceof MethodHandlePlaceholder) {
            return visitMethodHandlePlaceholder((MethodHandlePlaceholder) node, data);
        }

        return super.visitBytecodeConstant(node, data);
    }

    protected Void visitMethodHandlePlaceholder(final MethodHandlePlaceholder node, final Void data) {
        final TypeDeclaration currentType = this.currentType;
        final TypeDefinition currentTypeDefinition = currentType != null ? currentType.getUserData(Keys.TYPE_DEFINITION) : null;
        final MethodHandle handle = node.getHandle();

        if (currentTypeDefinition == null || handle == null || resolver == null) {
            return null;
        }

        boolean needsInsertion = false;
        LcdMHHelperBuilder hb = helpers.get(handle);

        if (hb == null) {
            needsInsertion = true;
            helpers.put(handle, hb = new LcdMHHelperBuilder(currentType, currentTypeDefinition, handle));
        }

        if (hb.build()) {
            final Expression replacement = makeType(hb.definition).member(hb.definition.fdHandle);

            node.replaceWith(replacement);

            replacement.getParent().insertChildBefore(replacement, new Comment(" ldc_method_handle(!) ", CommentType.MultiLine), Roles.COMMENT);

            if (needsInsertion) {
                currentType.getMembers().insertAfter(currentType.getMembers().lastOrNullObject(), hb.declaration);

                if (hb.extraLookupField != null) {
                    currentType.getMembers().insertBefore(hb.declaration, hb.extraLookupField);
                }

                final AstNode commentAnchor = hb.declaration.getFirstChild();

                hb.declaration.insertChildBefore(commentAnchor,
                                                 new Comment(" This helper class was generated by Procyon to approximate the behavior of a"),
                                                 Roles.COMMENT);

                hb.declaration.insertChildBefore(commentAnchor,
                                                 new Comment(" MethodHandle constant that cannot (currently) be represented in Java code."),
                                                 Roles.COMMENT);
            }
        }

        return null;
    }

    @SuppressWarnings("DuplicatedCode")
    protected final class LcdMHHelperBuilder {
        final static String T_DESC_METHOD_HANDLE = "java/lang/invoke/MethodHandle";
        final static String F_DESC_ENSURE_HANDLE = "L" + T_DESC_METHOD_HANDLE + ";";
        final static String M_DESC_ENSURE_HANDLE = "()" + F_DESC_ENSURE_HANDLE;

        final TypeDeclaration parentDeclaration;
        final TypeReference parentType;
        final MethodHandle handle;
        final TypeReference callSiteType;
        final TypeReference methodHandleType;
        final TypeReference methodTypeType;
        final TypeReference methodHandlesType;
        final TypeReference lookupType;
        final MethodReference handleMethod;
        final MethodReference ensureHandleMethod;
        final HelperTypeDefinition definition;
        final int generatedTypeId;

        Boolean alreadyBuilt;
        TypeDeclaration declaration;
        FieldDeclaration extraLookupField;
        MethodDeclaration handleDeclaration;

        LcdMHHelperBuilder(
            final TypeDeclaration parentDeclaration,
            final TypeReference parentType,
            final MethodHandle handle) {

            this.parentDeclaration = VerifyArgument.notNull(parentDeclaration, "parentDeclaration");
            this.parentType = VerifyArgument.notNull(parentType, "parentType");
            this.handle = VerifyArgument.notNull(handle, "handle");
            this.generatedTypeId = nextUniqueId();

            final TypeReference helperType = parser.parseTypeDescriptor(format("%s_%x", "ProcyonConstantHelper", generatedTypeId));

            this.callSiteType = parser.parseTypeDescriptor(T_DESC_CALL_SITE);
            this.methodTypeType = parser.parseTypeDescriptor(T_DESC_METHOD_TYPE);
            this.methodHandleType = parser.parseTypeDescriptor(T_DESC_METHOD_HANDLE);
            this.methodHandlesType = parser.parseTypeDescriptor(T_DESC_METHOD_HANDLES);
            this.definition = new HelperTypeDefinition(helperType, parentType);
            this.handleMethod = parser.parseMethod(definition, "handle", M_DESC_ENSURE_HANDLE);
            this.ensureHandleMethod = parser.parseMethod(definition, "ensureHandle", M_DESC_ENSURE_HANDLE);
            this.lookupType = parser.parseTypeDescriptor(T_DESC_LOOKUP);
        }

        boolean build() {
            final Boolean built = this.alreadyBuilt;

            if (built != null) {
                return built;
            }

            final TypeDeclaration declaration = new TypeDeclaration();

            this.declaration = declaration;

            final AstNodeCollection<JavaModifierToken> modifiers = this.declaration.getModifiers();

            for (final Flags.Flag modifier : Flags.asFlagSet((Flags.AccessFlags | Flags.MemberStaticClassFlags) &
                                                             (Flags.STATIC | Flags.FINAL | Flags.PRIVATE))) {
                modifiers.add(new JavaModifierToken(TextLocation.EMPTY, modifier));
            }

            declaration.setClassType(ClassType.CLASS);
            declaration.setName(definition.getSimpleName());
            declaration.putUserData(Keys.TYPE_REFERENCE, definition.selfReference);
            declaration.putUserData(Keys.TYPE_DEFINITION, definition);

            final AstNodeCollection<EntityDeclaration> members = declaration.getMembers();

            members.add(buildHandleField());
            members.add(buildTypeInitializer());

            this.alreadyBuilt = true;
            return true;
        }

        FieldDeclaration buildHandleField() {
            return declareField(definition.fdHandle, Expression.NULL, 0);
        }

        VariableDeclarationStatement makeMethodTypeVariableDeclaration() {
            final Variable v = new Variable();
            final VariableDeclarationStatement vd = new VariableDeclarationStatement(makeType(methodTypeType), "type", makeMethodType(handle.getMethod()));

            v.setGenerated(false);
            v.setName("type");
            v.setType(methodTypeType);
            vd.addModifier(Flags.Flag.FINAL);

            vd.putUserData(Keys.VARIABLE, v);

            return vd;
        }

        VariableDeclarationStatement makeHandleVariableDeclaration() {
            final Variable v = new Variable();
            final VariableDeclarationStatement vd = new VariableDeclarationStatement(makeType(methodHandleType), "handle");

            v.setGenerated(false);
            v.setName("handle");
            v.setType(methodHandleType);
            vd.putUserData(Keys.VARIABLE, v);

            return vd;
        }

        @SuppressWarnings("CommentedOutCode")
        MethodDeclaration buildTypeInitializer() {
            //
            // private static final MethodHandles.Lookup __PROCYON__LOOKUP__ = MethodHandles.lookup();
            //
            // private static final class ProcyonConstantHelper
            // {
            //     static final MethodHandle HANDLE;
            //
            //     static {
            //         MethodHandle handle;
            //         final MethodType type = MethodType.methodType(Object.class, MethodHandles.Lookup.class, MutableCallSite.class, String.class);
            //         try {
            //             handle = declaring_type.__PROCYON__LOOKUP__.findStatic(declaring_type.class, "handle", type);
            //         }
            //         catch (final ReflectiveOperationException e) {
            //             handle = MethodHandles.permuteArguments(MethodHandles.insertArguments(MethodHandles.throwException(type.returnType(), e.getClass()), 0, e), type);
            //         }
            //         ProcyonConstantHelper.HANDLE = handle;
            //     }
            // }
            //
            final MethodReference insertArguments = parser.parseMethod(methodHandlesType, "insertArguments", M_DESC_INSERT_ARGUMENTS);
            final MethodReference permuteArguments = parser.parseMethod(methodHandlesType, "permuteArguments", M_DESC_PERMUTE_ARGUMENTS);
            final MethodReference throwException = parser.parseMethod(methodHandlesType, "throwException", M_DESC_THROW_EXCEPTION);
            final MethodReference returnType = parser.parseMethod(methodTypeType, "returnType", M_DESC_RETURN_TYPE);
            final MethodReference getClass = parser.parseMethod(BuiltinTypes.Object, "getClass", M_DESC_GET_CLASS);
            final TypeReference reflectionException = parser.parseTypeDescriptor(T_DESC_REFLECTION_EXCEPTION);
            final MethodDeclaration declaration = newMethod(definition.mdTypeInit);
            final VariableDeclarationStatement handle = makeHandleVariableDeclaration();
            final VariableDeclarationStatement type = makeMethodTypeVariableDeclaration();
            final TryCatchStatement tryCatch = new TryCatchStatement();
            final Variable vHandle = handle.getUserData(Keys.VARIABLE);
            final Variable vType = type.getUserData(Keys.VARIABLE);

            final Expression lookup;
            final MethodReference lookupMethod = parser.parseMethod(methodHandlesType, "lookup", M_SIGNATURE_LOOKUP);

            if (context.isSupported(LanguageFeature.PRIVATE_LOOKUP)) {
                lookup = makeType(methodHandlesType).invoke(parser.parseMethod(methodHandlesType, "privateLookupIn", M_SIGNATURE_LOOKUP),
                                                            makeType(currentType.getUserData(Keys.TYPE_DEFINITION)).classOf(),
                                                            makeType(methodHandlesType).invoke(lookupMethod));
            }
            else {
                final FieldDefinition lookupField = new FieldDefinition(lookupType) {{
                    this.setName(format("__PROCYON__LOOKUP_%x__", generatedTypeId));
                    this.setDeclaringType(currentType.getUserData(Keys.TYPE_DEFINITION));
                    this.setFlags((Flags.AccessFlags | Flags.VarFlags) & (Flags.PRIVATE | Flags.STATIC));
                }};

                extraLookupField = declareField(lookupField, makeType(methodHandlesType).invoke(lookupMethod), Flags.STATIC | Flags.FINAL);

                lookup = makeReference(lookupField);
            }

            tryCatch.setTryBlock(
                new BlockStatement(
                    new ExpressionStatement(
                        new AssignmentExpression(varReference(vHandle),
                                                 makeMethodHandle(lookup, this.handle, varReference(type)))
                    )
                )
            );

            final CatchClause cc = new CatchClause();

            cc.setVariableName("e");
            cc.addVariableModifier(Flags.Flag.FINAL);
            cc.putUserData(Keys.VARIABLE, makeCatchVariable("e", reflectionException));
            cc.getExceptionTypes().add(makeType(reflectionException));

            final Variable vException = cc.getUserData(Keys.VARIABLE);

            cc.setBody(
                new BlockStatement(
                    new ExpressionStatement(
                        new AssignmentExpression(
                            varReference(vHandle),
                            makeType(methodHandlesType).invoke(
                                permuteArguments,
                                makeType(methodHandlesType).invoke(
                                    insertArguments,
                                    makeType(methodHandlesType).invoke(
                                        throwException,
                                        varReference(vType).invoke(returnType),
                                        varReference(vException).invoke(getClass)
                                    ),
                                    new PrimitiveExpression(0),
                                    varReference(vException)
                                ),
                                varReference(vType)
                            )
                        )
                    )
                )
            );

            tryCatch.getCatchClauses().add(cc);

            declaration.setBody(
                new BlockStatement(handle,
                                   type,
                                   tryCatch,
                                   new ExpressionStatement(
                                       new AssignmentExpression(makeReference(definition.fdHandle),
                                                                varReference(vHandle))
                                   )
                )
            );

            this.handleDeclaration = declaration;

            return declaration;
        }

        // <editor-fold defaultstate="collapsed" desc="HelperTypeDefinition Class">

        private final class HelperTypeDefinition extends TypeDefinition {
            final TypeReference selfReference;
            final FieldDefinition fdHandle;
            final MethodDefinition mdTypeInit;

            @SuppressWarnings("DuplicatedCode")
            HelperTypeDefinition(final TypeReference selfReference, final TypeReference parentType) {
                super(resolver(parentType));

                this.selfReference = selfReference;

                setPackageName(selfReference.getPackageName());
                setSimpleName(selfReference.getSimpleName());
                setBaseType(BuiltinTypes.Object);
                setFlags((Flags.AccessFlags | Flags.MemberClassFlags) & (Flags.PRIVATE | Flags.FINAL | Flags.STATIC));
                setDeclaringType(parentType);

                final TypeDefinition resolvedParent = parentType.resolve();

                if (resolvedParent != null) {
                    setResolver(resolvedParent.getResolver());
                    setCompilerVersion(resolvedParent.getCompilerMajorVersion(), resolvedParent.getCompilerMinorVersion());
                }
                else {
                    setResolver(resolver());
                    setCompilerVersion(CompilerTarget.JDK1_7.majorVersion, CompilerTarget.JDK1_7.minorVersion);
                }

                final TypeDefinition self = this;

                fdHandle = new FieldDefinition(methodHandleType) {{
                    this.setName("HANDLE");
                    this.setDeclaringType(self);
                    this.setFlags((Flags.AccessFlags | Flags.VarFlags) & (Flags.FINAL | Flags.STATIC));
                }};

                mdTypeInit = new MethodDefinition() {{
                    this.setName(MethodDefinition.STATIC_INITIALIZER_NAME);
                    this.setDeclaringType(self);
                    this.setFlags(Flags.STATIC);
                    this.setReturnType(BuiltinTypes.Void);
                }};

                final Collection<FieldDefinition> fields = getDeclaredFieldsInternal();
                final Collection<MethodDefinition> methods = getDeclaredMethodsInternal();

                fields.add(fdHandle);
                methods.add(mdTypeInit);
            }
        }

        // </editor-fold>
    }
}
