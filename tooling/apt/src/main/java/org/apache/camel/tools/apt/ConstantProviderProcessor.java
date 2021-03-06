/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.tools.apt;

import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import org.apache.camel.spi.annotations.ConstantProvider;

import static org.apache.camel.tools.apt.AnnotationProcessorHelper.dumpExceptionToErrorFile;
import static org.apache.camel.tooling.util.Strings.canonicalClassName;

@SupportedAnnotationTypes({"org.apache.camel.spi.annotations.ConstantProvider"})
public class ConstantProviderProcessor extends AbstractCamelAnnotationProcessor {

    boolean acceptClass(Element element) {
        return true;
    }

    @Override
    protected void doProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) throws Exception {
        TypeElement constantAnnotationType = this.processingEnv.getElementUtils().getTypeElement("org.apache.camel.spi.annotations.ConstantProvider");
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(constantAnnotationType);

        Map<String, Element> constantClasses = new TreeMap<>();
        for (Element element : elements) {
            if (element instanceof TypeElement) {
                TypeElement te = (TypeElement) element;

                // we only support top-level classes (not inner classes)
                if (!te.getNestingKind().isNested() && acceptClass(te)) {
                    final String javaTypeName = canonicalClassName(te.getQualifiedName().toString());
                    constantClasses.put(javaTypeName, element);
                }
            }
        }

        // skip all converter classes from core as we just want to use the optimized TypeConverterLoader files
        constantClasses.forEach((k, v) -> {
            String fqn = v.getAnnotation(ConstantProvider.class).value();
            Map<String, String> fields = new TreeMap<>(String::compareToIgnoreCase);

            Set<Element> set = new HashSet<>(v.getEnclosedElements());
            for (VariableElement field : ElementFilter.fieldsIn(set)) {
                TypeMirror fieldType = field.asType();
                String fullTypeClassName = fieldType.toString();
                if (String.class.getName().equals(fullTypeClassName)) {
                    String name = field.getSimpleName().toString();
                    String text = (String) field.getConstantValue();
                    fields.put(name, text);
                }
            }

            if (!fields.isEmpty()) {
                generateConstantProviderClass(fqn, fields);
            }
        });
    }

    private void generateConstantProviderClass(String fqn, Map<String, String> fields) {
        String pn = fqn.substring(0, fqn.lastIndexOf('.'));
        String cn = fqn.substring(fqn.lastIndexOf('.') + 1);

        try (Writer w = processingEnv.getFiler().createSourceFile(fqn).openWriter()) {
            w.write("/* Generated by org.apache.camel:apt */\n");
            w.write("package " + pn + ";\n");
            w.write("\n");
            w.write("import java.util.HashMap;\n");
            w.write("import java.util.Map;\n");
            w.write("\n");
            w.write("/**\n");
            w.write(" * Source code generated by org.apache.camel:apt\n");
            w.write(" */\n");
            w.write("public class " + cn + " {\n");
            w.write("\n");
            w.write("    private static final Map<String, String> MAP;\n");
            w.write("    static {\n");
            w.write("        Map<String, String> map = new HashMap<>(" + fields.size() + ");\n");
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                w.write("        map.put(\"" + entry.getKey() + "\", \"" + entry.getValue() + "\");\n");
            }
            w.write("        MAP = map;\n");
            w.write("    }\n");
            w.write("\n");
            w.write("    public static String lookup(String key) {\n");
            w.write("        return MAP.get(key);\n");
            w.write("    }\n");
            w.write("}\n");
            w.write("\n");
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Unable to generate source code file: " + fqn + ": " + e.getMessage());
            dumpExceptionToErrorFile("camel-apt-error.log", "Unable to generate source code file: " + fqn, e);
        }
    }

}
