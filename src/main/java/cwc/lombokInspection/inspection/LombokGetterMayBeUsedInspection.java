package cwc.lombokInspection.inspection;

import static com.intellij.util.ObjectUtils.tryCast;

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEmptyStatement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
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

public class LombokGetterMayBeUsedInspection extends LombokGetterOrSetterMayBeUsedInspection {
  Pattern pattern = Pattern.compile("[a-z][A-Z].*");

  @Override
  @NotNull
  protected @NonNls String getAnnotationName() {
    return LombokClassNames.GETTER;
  }

  @Override
  @NotNull
  protected @Nls String getFieldErrorMessage(String fieldName) {
    return InspectionBundle.message("inspection.lombok.getter.may.be.used.field.message",
                                fieldName);
  }

  @Override
  @NotNull
  protected @Nls String getClassErrorMessage(String className) {
    return InspectionBundle.message("inspection.lombok.getter.may.be.used.class.message",
                                className);
  }

  @Override
  protected boolean processMethod(
    @NotNull PsiMethod method,
    @NotNull List<Pair<PsiField, PsiMethod>> instanceCandidates,
    @NotNull List<Pair<PsiField, PsiMethod>> staticCandidates
  ) {
    final PsiType returnType = method.getReturnType();
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)
        || method.isConstructor()
        || method.hasParameters()
        || method.getThrowsTypes().length != 0
        || method.hasModifierProperty(PsiModifier.FINAL)
        || method.hasModifierProperty(PsiModifier.ABSTRACT)
        || method.hasModifierProperty(PsiModifier.SYNCHRONIZED)
        || method.hasModifierProperty(PsiModifier.NATIVE)
        || method.hasModifierProperty(PsiModifier.STRICTFP)
        || method.getAnnotations().length != 0
        || PsiTypes.voidType().equals(returnType)
        || returnType == null
        || returnType.getAnnotations().length != 0
        || !method.isWritable()) {
      return false;
    }
    final String methodName = method.getName();
    final boolean isBooleanType = PsiTypes.booleanType().equals(returnType);
    if (isBooleanType ? !methodName.startsWith("is") : !methodName.startsWith("get")) {
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
    final PsiReturnStatement returnStatement = tryCast(methodStatements[0], PsiReturnStatement.class);
    if (returnStatement == null) {
      return false;
    }
    final PsiReferenceExpression targetRef = tryCast(
      PsiUtil.skipParenthesizedExprDown(returnStatement.getReturnValue()), PsiReferenceExpression.class);
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
    if (!fieldName.equals(fieldIdentifier) && !StringUtil.capitalize(fieldName).equals(fieldIdentifier)) {
      return false;
    }

    final boolean isMethodStatic = method.hasModifierProperty(PsiModifier.STATIC);
    final PsiField field = psiClass.findFieldByName(fieldIdentifier, false);
    if (field == null
        || !field.isWritable()
        || isMethodStatic != field.hasModifierProperty(PsiModifier.STATIC)
        || !field.getType().equals(returnType)) {
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
    return InspectionBundle.message("inspection.lombok.getter.may.be.used.fix.name", text);
  }

  @Override
  @NotNull
  protected @Nls String getFixFamilyName() {
    return InspectionBundle.message("inspection.lombok.getter.may.be.used.fix.family.name");
  }
}
