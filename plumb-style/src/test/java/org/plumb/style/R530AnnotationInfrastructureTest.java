package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.style.annotations.AnnotationExtractor;
import org.plumb.style.annotations.AnnotationFacts;

class R530AnnotationInfrastructureTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private CompilationUnit parse(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    return ps.unit();
  }

  private ClassOrInterfaceDeclaration theClass(CompilationUnit cu) {
    return cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
  }

  @Test
  void simpleNameMatchesNonNull() {
    CompilationUnit cu = parse("class N {" + " @NonNull" + " String f;" + "}");
    FieldDeclaration f = theClass(cu).getFieldByName("f").orElseThrow();
    Set<String> names = AnnotationExtractor.namesOf(f);
    assertThat(names).contains("NonNull");
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.NONNULL)).isTrue();
  }

  @Test
  void importedFqualifierMatches() {
    CompilationUnit cu =
        parse("import javax.annotation.Nonnull;" + "class N {" + " @Nonnull" + " String f;" + "}");
    FieldDeclaration f = theClass(cu).getFieldByName("f").orElseThrow();
    Set<String> names = AnnotationExtractor.namesOf(f);
    assertThat(names).contains("Nonnull");
    assertThat(names).contains("javax.annotation.Nonnull");
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.NONNULL)).isTrue();
  }

  @Test
  void fqnAsWrittenMatches() {
    CompilationUnit cu =
        parse("class N {" + " @org.jetbrains.annotations.NotNull" + " String f;" + "}");
    FieldDeclaration f = theClass(cu).getFieldByName("f").orElseThrow();
    Set<String> names = AnnotationExtractor.namesOf(f);
    assertThat(names).contains("NotNull");
    assertThat(names).contains("org.jetbrains.annotations.NotNull");
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.NONNULL)).isTrue();
  }

  @Test
  void lombokNonNullMatches() {
    CompilationUnit cu = parse("class N {" + " @lombok.NonNull" + " String f;" + "}");
    FieldDeclaration f = theClass(cu).getFieldByName("f").orElseThrow();
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.NONNULL))
        .as("lombok.NonNull is in the NONNULL set")
        .isTrue();
  }

  @Test
  void noAnnotationHasNoNames() {
    CompilationUnit cu = parse("class N { String f; }");
    FieldDeclaration f = theClass(cu).getFieldByName("f").orElseThrow();
    assertThat(AnnotationExtractor.namesOf(f)).isEmpty();
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.NONNULL)).isFalse();
  }

  @Test
  void unrelatedAnnotationDoesNotMatch() {
    CompilationUnit cu = parse("class N {" + " @Deprecated" + " String f;" + "}");
    FieldDeclaration f = theClass(cu).getFieldByName("f").orElseThrow();
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.NONNULL)).isFalse();
    assertThat(AnnotationExtractor.hasAny(f, AnnotationFacts.JPA_ID)).isFalse();
  }

  @Test
  void jpaIdFieldDetection() {
    CompilationUnit cu =
        parse("import javax.persistence.Id;" + "class N {" + " @Id" + " Long id;" + "}");
    FieldDeclaration id = theClass(cu).getFieldByName("id").orElseThrow();
    assertThat(AnnotationExtractor.hasAny(id, AnnotationFacts.JPA_ID)).isTrue();
    assertThat(AnnotationExtractor.hasAny(id, AnnotationFacts.NONNULL)).isFalse();
  }

  @Test
  void entityClassDetection() {
    CompilationUnit cu =
        parse("import javax.persistence.Entity;\n" + "@Entity\n" + "class N { Long id; }");
    // The class N has @Entity on it (set by the import alias).
    ClassOrInterfaceDeclaration cls = cu.getClassByName("N").orElseThrow();
    assertThat(AnnotationExtractor.isEntityClass(cls)).isTrue();
  }

  @Test
  void notEntityClassByDefault() {
    CompilationUnit cu = parse("class N { Long id; }");
    ClassOrInterfaceDeclaration cls = theClass(cu);
    assertThat(AnnotationExtractor.isEntityClass(cls)).isFalse();
  }

  @Test
  void fieldsAnnotatedWithFindsAll() {
    CompilationUnit cu =
        parse(
            "import javax.persistence.Id;"
                + "class N {"
                + " @Id Long id;"
                + " String name;"
                + " @Id Long tenantId;"
                + "}");
    ClassOrInterfaceDeclaration cls = theClass(cu);
    List<FieldDeclaration> ids =
        AnnotationExtractor.fieldsAnnotatedWith(cls, AnnotationFacts.JPA_ID);
    assertThat(ids).hasSize(2);
    assertThat(ids.get(0).getVariable(0).getNameAsString()).isEqualTo("id");
    assertThat(ids.get(1).getVariable(0).getNameAsString()).isEqualTo("tenantId");
  }

  @Test
  void methodAnnotationDetection() {
    CompilationUnit cu =
        parse(
            "import org.springframework.transaction.annotation.Transactional;"
                + "class N {"
                + " @Transactional"
                + " void save() {}"
                + "}");
    MethodDeclaration save = theClass(cu).getMethodsByName("save").get(0);
    assertThat(AnnotationExtractor.hasAny(save, AnnotationFacts.JPA_TRANSACTIONAL)).isTrue();
  }
}
