// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package cwc.lombokInspection.inspection;

import static com.intellij.util.ObjectUtils.tryCast;

import com.intellij.lang.jvm.JvmModifier;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEmptyStatement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;

import cwc.lombokInspection.InspectionBundle;
import cwc.lombokInspection.LombokClassNames;

import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class LombokSetterMayBeUsedInspection extends LombokGetterOrSetterMayBeUsedInspection {
  Pattern pattern = Pattern.compile("[a-z][A-Z].*");

  @Override
  @NotNull
  protected @NonNls String getAnnotationName() {
    return LombokClassNames.SETTER;
  }

  @Override
  @NotNull
  protected @Nls String getFieldErrorMessage(String fieldName) {
    return InspectionBundle.message("inspection.lombok.setter.may.be.used.field.message",
                                fieldName);
  }

  @Override
  @NotNull
  protected @Nls String getClassErrorMessage(String className) {
    return InspectionBundle.message("inspection.lombok.setter.may.be.used.class.message",
                                className);
  }

  @Override
  protected boolean processMethod(
    @NotNull PsiMethod method,
    @NotNull List<Pair<PsiField, PsiMethod>> instanceCandidates,
    @NotNull List<Pair<PsiField, PsiMethod>> staticCandidates
  ) {
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)
        || method.isConstructor()
        || !method.hasParameters()
        || method.getParameterList().getParameters().length != 1
        || 0 < method.getThrowsTypes().length
        || method.hasModifierProperty(PsiModifier.FINAL)
        || method.hasModifierProperty(PsiModifier.ABSTRACT)
        || method.hasModifierProperty(PsiModifier.SYNCHRONIZED)
        || method.hasModifierProperty(PsiModifier.NATIVE)
        || method.hasModifierProperty(PsiModifier.STRICTFP)
        || 0 < method.getAnnotations().length
        || !PsiTypes.voidType().equals(method.getReturnType())
        || !method.isWritable()) {
      return false;
    }
    final PsiParameter parameter = method.getParameterList().getParameters()[0];
    if (
      parameter.isVarArgs()
      || (
        parameter.getModifierList() != null
        && 0 < parameter.getModifierList().getChildren().length
        && (parameter.getModifierList().getChildren().length != 1 || !parameter.hasModifier(JvmModifier.FINAL))
      )
      || 0 < parameter.getAnnotations().length
    ) {
      return false;
    }
    final PsiType parameterType = parameter.getType();

    final String methodName = method.getName();
    if (!methodName.startsWith("set")) {
      return false;
    }

    final String fieldName = StringUtil.getPropertyName(methodName);
    if (StringUtil.isEmpty(fieldName)) {
      return false;
    }
    if (pattern.matcher(fieldName).matches()) {
      return false;
    }
    if (method.getBody() == null) {
      return false;
    }
    final PsiStatement @NotNull [] methodStatements = Arrays.stream(method.getBody().getStatements()).filter(e -> !(e instanceof PsiEmptyStatement)).toArray(PsiStatement[]::new);
    if (methodStatements.length != 1) {
      return false;
    }
    final PsiExpressionStatement assignmentStatement = tryCast(methodStatements[0], PsiExpressionStatement.class);
    if (assignmentStatement == null) {
      return false;
    }
    final PsiAssignmentExpression assignment = tryCast(assignmentStatement.getExpression(), PsiAssignmentExpression.class);
    if (assignment == null || assignment.getOperationTokenType() != JavaTokenType.EQ) {
      return false;
    }
    final PsiReferenceExpression sourceRef = tryCast(PsiUtil.skipParenthesizedExprDown(assignment.getRExpression()), PsiReferenceExpression.class);
    if (sourceRef == null || sourceRef.getQualifierExpression() != null) {
      return false;
    }
    final @Nullable String paramIdentifier = sourceRef.getReferenceName();
    if (paramIdentifier == null) {
      return false;
    }
    if (!paramIdentifier.equals(parameter.getName())) {
      return false;
    }
    final PsiReferenceExpression targetRef = tryCast(assignment.getLExpression(), PsiReferenceExpression.class);
    if (targetRef == null) {
      return false;
    }
    final @Nullable PsiExpression qualifier = targetRef.getQualifierExpression();
    final @Nullable PsiThisExpression thisExpression = tryCast(qualifier, PsiThisExpression.class);
    final PsiClass psiClass = PsiTreeUtil.getParentOfType(method, PsiClass.class);
    if (psiClass == null) {
      return false;
    }
    if (qualifier != null) {
      if (thisExpression == null) {
        return false;
      } else if (thisExpression.getQualifier() != null) {
        if (!thisExpression.getQualifier().isReferenceTo(psiClass)) {
          return false;
        }
      }
    }
    final @Nullable String fieldIdentifier = targetRef.getReferenceName();
    if (fieldIdentifier == null) {
      return false;
    }
    if (!fieldName.equals(fieldIdentifier) && !StringUtil.capitalize(fieldName).equals(fieldIdentifier)) {
      return false;
    }
    if (qualifier == null
        && paramIdentifier.equals(fieldIdentifier)) {
      return false;
    }

    final boolean isMethodStatic = method.hasModifierProperty(PsiModifier.STATIC);
    final PsiField field = psiClass.findFieldByName(fieldIdentifier, false);
    if (field == null
        || !field.isWritable()
        || isMethodStatic != field.hasModifierProperty(PsiModifier.STATIC)
        || !field.getType().equals(parameterType)) {
      return false;
    }
    if (isMethodStatic) {
      staticCandidates.add(Pair.pair(field, method));
    } else {
      instanceCandidates.add(Pair.pair(field, method));
    }
    return true;
  }

  @Override
  @NotNull
  protected @Nls String getFixName(String text) {
    return InspectionBundle.message("inspection.lombok.setter.may.be.used.fix.name", text);
  }

  @Override
  @NotNull
  protected @Nls String getFixFamilyName() {
    return InspectionBundle.message("inspection.lombok.setter.may.be.used.fix.family.name");
  }
}
