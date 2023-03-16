/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gds.collections.haa;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import org.neo4j.gds.collections.CollectionStep;

import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.stream.IntStream;

final class HugeAtomicArrayGenerator implements CollectionStep.Generator<HugeAtomicArrayValidation.Spec> {

    private static final ClassName PAGE_UTIL = ClassName.get("org.neo4j.gds.collections", "PageUtil");
    private static final ClassName ARRAY_UTIL = ClassName.get("org.neo4j.gds.collections", "ArrayUtil");


    @Override
    public TypeSpec generate(HugeAtomicArrayValidation.Spec spec) {
        var className = ClassName.get(spec.rootPackage().toString(), spec.className());
        var elementType = TypeName.get(spec.element().asType());
        var valueType = TypeName.get(spec.valueType());
        var unaryOperatorType = TypeName.get(spec.valueOperatorInterface());

        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.FINAL)
            .addSuperinterface(elementType)
            .addOriginatingElement(spec.element());

        // class annotation
        builder.addAnnotation(generatedAnnotation());

        // TODO split into paged and single

        // class fields
        var arrayHandle = arrayHandleField(valueType);
        var pageShift = pageShiftField(spec.pageShift());
        var pageSize = pageSizeField(pageShift);
        var pageMask = pageMaskField(pageSize);
        builder.addField(arrayHandle);
        builder.addField(pageShift);
        builder.addField(pageSize);
        builder.addField(pageMask);

        // instance fields
        var size = sizeField();
        var pages = pagesField(valueType);
        var memoryUsed = memoryUsedField(valueType);
        builder.addField(size);
        builder.addField(pages);
        builder.addField(memoryUsed);

        // constructor
        // TODO idx to value producer as a parameter
        builder.addMethod(ofMethod(valueType, className, pageMask, pageShift, pageSize));
        builder.addMethod(constructor(valueType));

        // instance methods
        builder.addMethod(getMethod(valueType, arrayHandle, pages, pageShift, pageMask));
        builder.addMethod(getAndAddMethod(valueType, arrayHandle, pages, pageShift, pageMask));
        builder.addMethod(setMethod(valueType, arrayHandle, pages, pageShift, pageMask));
        builder.addMethod(updateMethod(valueType, unaryOperatorType, arrayHandle, pages, pageShift, pageMask));
        builder.addMethod(compareAndSetMethod(valueType, arrayHandle, pages, pageShift, pageMask));
        builder.addMethod(compareAndExchangeMethod(valueType, arrayHandle, pages, pageShift, pageMask));
        builder.addMethod(newCursorMethod(valueType, size, pages));
        builder.addMethod(sizeMethod(size));
        builder.addMethod(sizeOfMethod(memoryUsed));
        builder.addMethod(setAllMethod(valueType, pages));
        builder.addMethod(releaseMethod(pages, memoryUsed));
        builder.addMethod(copyToMethod(elementType, className, valueType, size, pages));

//        // GrowingBuilder
//        builder.addType(GrowingBuilderGenerator.growingBuilder(
//            className,
//            elementType,
//            builderType,
//            valueType,
//            pageSize,
//            pageShift,
//            pageMask
//        ));

        return builder.build();
    }

    private static TypeName valueArrayType(TypeName valueType) {
        return ArrayTypeName.of(valueType);
    }

    private static AnnotationSpec generatedAnnotation() {
        return AnnotationSpec.builder(Generated.class)
            .addMember("value", "$S", HugeAtomicArrayGenerator.class.getCanonicalName())
            .build();
    }

    private static FieldSpec pageShiftField(int pageShift) {
        return FieldSpec
            .builder(TypeName.INT, "PAGE_SHIFT", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$L", pageShift)
            .build();
    }

    private static FieldSpec pageSizeField(FieldSpec pageShiftField) {
        return FieldSpec
            .builder(TypeName.INT, "PAGE_SIZE", Modifier.STATIC, Modifier.FINAL)
            .initializer("1 << $N", pageShiftField)
            .build();
    }

    private static FieldSpec pageMaskField(FieldSpec pageSizeField) {
        return FieldSpec
            .builder(TypeName.INT, "PAGE_MASK", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$N - 1", pageSizeField)
            .build();
    }

    private static FieldSpec arrayHandleField(TypeName valueType) {
        return FieldSpec
            .builder(VarHandle.class, "ARRAY_HANDLE", Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
            .initializer("$T.arrayElementVarHandle($T.class)", MethodHandles.class, valueArrayType(valueType))
            .build();
    }

    private static FieldSpec sizeField() {
        return FieldSpec
            .builder(TypeName.LONG, "size", Modifier.PRIVATE, Modifier.FINAL)
            .build();
    }

    private static FieldSpec pagesField(TypeName valueType) {
        return FieldSpec
            .builder(valueArrayType(valueArrayType(valueType)), "pages", Modifier.PRIVATE)
            .build();
    }

    private static FieldSpec memoryUsedField(TypeName valueType) {
        return FieldSpec
            .builder(valueType, "memoryUsed", Modifier.PRIVATE, Modifier.FINAL)
            .build();
    }

    private static MethodSpec ofMethod(
        TypeName valueType,
        ClassName implType,
        FieldSpec pageMask,
        FieldSpec pageShift,
        FieldSpec pageSize
    ) {
        return MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(TypeName.LONG, "size")
            .returns(implType)
            .addStatement("int numPages = $T.numPagesFor(size, $N, $N)", PAGE_UTIL, pageShift, pageMask)
            .addStatement("$T pages = new $T[numPages][]", valueArrayType(valueArrayType(valueType)), valueType)
            .addStatement("int lastPageSize = $T.exclusiveIndexOfPage(size, $N)", PAGE_UTIL, pageMask)
            .addStatement("int lastPageIndex = pages.length - 1")
            .addStatement("$T.range(0, lastPageIndex).forEach(idx -> pages[idx] = new $T[$N])", IntStream.class, valueType, pageSize)
            .addStatement("pages[lastPageIndex] = new $T[lastPageSize]", valueType)
            // FIXME either move memoryUsed out of impl correctly
            .addStatement("long memoryUsed = -1")
            .addStatement("return new $T(size, pages, memoryUsed)", implType)
            .build();
    }

    private static MethodSpec constructor(TypeName valueType) {
        return MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(TypeName.LONG, "size")
            .addParameter(valueArrayType(valueArrayType(valueType)), "pages")
            .addParameter(TypeName.LONG, "memoryUsed")
            .addStatement("this.size = size")
            .addStatement("this.pages = pages")
            .addStatement("this.memoryUsed = memoryUsed")
            .build();
    }

    private static MethodSpec getMethod(
        TypeName valueType,
        FieldSpec arrayHandle,
        FieldSpec pages,
        FieldSpec pageShift,
        FieldSpec pageMask
    ) {
        return MethodSpec.methodBuilder("get")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "index")
            .returns(valueType)
            .addCode(CodeBlock.builder()
                .addStatement("int pageIndex = $T.pageIndex(index, $N)", PAGE_UTIL, pageShift)
                .addStatement("int indexInPage = $T.indexInPage(index, $N)", PAGE_UTIL, pageMask)
                .addStatement("return ($T) $N.getVolatile($N[pageIndex], indexInPage)", valueType, arrayHandle, pages)
                .build())
            .build();
    }

    private static MethodSpec getAndAddMethod(
        TypeName valueType,
        FieldSpec arrayHandle,
        FieldSpec pages,
        FieldSpec pageShift,
        FieldSpec pageMask
    ) {
        return MethodSpec.methodBuilder("getAndAdd")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "index")
            .addParameter(TypeName.LONG, "delta")
            .returns(valueType)
            .addCode(CodeBlock.builder()
                .addStatement("int pageIndex = $T.pageIndex(index, $N)", PAGE_UTIL, pageShift)
                .addStatement("int indexInPage = $T.indexInPage(index, $N)", PAGE_UTIL, pageMask)
                .addStatement("$T page = $N[pageIndex]", valueArrayType(valueType), pages)
                .addStatement("$1T prev = ($1T) $2N.getAcquire(page, indexInPage)", valueType, arrayHandle)
                .beginControlFlow("while (true)")
                .addStatement("$T next = prev + delta", valueType)
                .addStatement("$1T current = ($1T) $2N.compareAndExchangeRelease(page, indexInPage, prev, next)", valueType, arrayHandle)
                .beginControlFlow("if (prev == current)")
                .addStatement("return prev")
                .endControlFlow()
                .addStatement("prev = current")
                .endControlFlow()
                .build())
            .build();
    }

    private static MethodSpec setMethod(
        TypeName valueType,
        FieldSpec arrayHandle,
        FieldSpec pages,
        FieldSpec pageShift,
        FieldSpec pageMask
    ) {
        return MethodSpec.methodBuilder("set")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "index")
            .addParameter(valueType, "value")
            .returns(TypeName.VOID)
            .addCode(CodeBlock.builder()
                .addStatement("int pageIndex = $T.pageIndex(index, $N)", PAGE_UTIL, pageShift)
                .addStatement("int indexInPage = $T.indexInPage(index, $N)", PAGE_UTIL, pageMask)
                .addStatement("$N.setVolatile($N[pageIndex], indexInPage, value)", arrayHandle, pages)
                .build())
            .build();
    }

    private static MethodSpec compareAndSetMethod(
        TypeName valueType,
        FieldSpec arrayHandle,
        FieldSpec pages,
        FieldSpec pageShift,
        FieldSpec pageMask
    ) {
        return MethodSpec.methodBuilder("compareAndSet")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "index")
            .addParameter(valueType, "expected")
            .addParameter(valueType, "update")
            .returns(TypeName.BOOLEAN)
            .addCode(CodeBlock.builder()
                .addStatement("int pageIndex = $T.pageIndex(index, $N)", PAGE_UTIL, pageShift)
                .addStatement("int indexInPage = $T.indexInPage(index, $N)", PAGE_UTIL, pageMask)
                .addStatement("return $N.compareAndSet($N[pageIndex], indexInPage, expected, update)", arrayHandle, pages)
                .build())
            .build();
    }

    private static MethodSpec compareAndExchangeMethod(
        TypeName valueType,
        FieldSpec arrayHandle,
        FieldSpec pages,
        FieldSpec pageShift,
        FieldSpec pageMask
    ) {
        return MethodSpec.methodBuilder("compareAndExchange")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "index")
            .addParameter(valueType, "expected")
            .addParameter(valueType, "update")
            .returns(valueType)
            .addCode(CodeBlock.builder()
                .addStatement("int pageIndex = $T.pageIndex(index, $N)", PAGE_UTIL, pageShift)
                .addStatement("int indexInPage = $T.indexInPage(index, $N)", PAGE_UTIL, pageMask)
                .addStatement("return ($T) $N.compareAndExchange($N[pageIndex], indexInPage, expected, update)", valueType, arrayHandle, pages)
                .build())
            .build();
    }

    private static MethodSpec updateMethod(
        TypeName valueType,
        TypeName unaryOperatorType,
        FieldSpec arrayHandle,
        FieldSpec pages,
        FieldSpec pageShift,
        FieldSpec pageMask
    ) {
        return MethodSpec.methodBuilder("update")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "index")
            .addParameter(unaryOperatorType, "updateFunction")
            .returns(TypeName.VOID)
            .addCode(CodeBlock.builder()
                .addStatement("int pageIndex = $T.pageIndex(index, $N)", PAGE_UTIL, pageShift)
                .addStatement("int indexInPage = $T.indexInPage(index, $N)", PAGE_UTIL, pageMask)
                .addStatement("$T page = $N[pageIndex]", valueArrayType(valueType), pages)
                .addStatement("$1T prev = ($1T) $2N.getAcquire(page, indexInPage)", valueType, arrayHandle)
                .beginControlFlow("while (true)")
                .addStatement("$T next = updateFunction.apply(prev)", valueType)
                .addStatement("$1T current = ($1T) $2N.compareAndExchangeRelease(page, indexInPage, prev, next)", valueType, arrayHandle)
                .beginControlFlow("if (prev == current)")
                .addStatement("return")
                .endControlFlow()
                .addStatement("prev = current")
                .endControlFlow()
                .build())
            .build();
    }


    private static MethodSpec newCursorMethod(
        TypeName valueType,
        FieldSpec size,
        FieldSpec pages
    ) {
        ClassName hugeCursorType = ClassName.get("org.neo4j.gds.collections.cursor", "HugeCursor");
        ParameterizedTypeName hugeCursorGenericType = ParameterizedTypeName.get(hugeCursorType, valueArrayType(valueType));

        return MethodSpec.methodBuilder("newCursor")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(hugeCursorGenericType)
            .addStatement("return new $T.PagedCursor<>($N, $N)", hugeCursorType, size, pages)
            .build();
    }

    private static MethodSpec sizeMethod(FieldSpec size) {
        return MethodSpec.methodBuilder("size")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.LONG)
            .addStatement("return $N", size)
            .build();
    }

    private static MethodSpec sizeOfMethod(FieldSpec memoryUsed) {
        return MethodSpec.methodBuilder("sizeOf")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.LONG)
            .addStatement("return $N", memoryUsed)
            .build();
    }

    private static MethodSpec setAllMethod(TypeName valueType, FieldSpec pages) {
        return MethodSpec.methodBuilder("setAll")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.LONG, "value")
            .returns(TypeName.VOID)
            .beginControlFlow("for ($T page: $N)", valueArrayType(valueType), pages)
            .addStatement("$T.fill(page, value)", ClassName.get(Arrays.class))
            .endControlFlow()
            .addStatement("$T.storeStoreFence()", ClassName.get(VarHandle.class))
            .build();
    }

    private static MethodSpec releaseMethod(FieldSpec pages, FieldSpec memoryUsed) {
        return MethodSpec.methodBuilder("release")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.LONG)
            .beginControlFlow("if ($N != null)", pages)
            .addStatement("$N = null", pages)
            .addStatement("return $N", memoryUsed)
            .endControlFlow()
            .addStatement("return 0L")
            .build();
    }

    private static MethodSpec copyToMethod(TypeName interfaceType, ClassName implType, TypeName valueType, FieldSpec size, FieldSpec pages) {
        // FIXME the default value is only used for the copyTo but still needs to be set
        long defaultValue = 0L;

        // FIXME also handle SingleHugeAtomicLongArray case
        return MethodSpec.methodBuilder("copyTo")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(interfaceType, "dest")
            .addParameter(TypeName.LONG, "length")
            .returns(TypeName.VOID)
            .beginControlFlow("if (!(dest instanceof $T))", implType)
            .addStatement("throw new $T(\"Can only handle Paged version for now\")", RuntimeException.class)
            .endControlFlow()
            .addStatement("$1T dst = ($1T) dest", implType)
            .beginControlFlow("if (length > $N)", size)
            .addStatement("length = $N", size)
            .endControlFlow()
            .beginControlFlow("if (length > dst.size())")
            .addStatement("length = dst.size()")
            .endControlFlow()
            .addStatement("int pageLen = Math.min($1N.length, dst.$1N.length)", pages)
            .addStatement("int lastPage = pageLen - 1")
            .addStatement("long remaining = length")
            .beginControlFlow("for(int i = 0; i < lastPage; i++)")
            .addStatement("$T page = $N[i]", valueArrayType(valueType), pages)
            .addStatement("$T dstPage = dst.$N[i]", valueArrayType(valueType), pages)
            .addStatement("$T.arraycopy(page, 0, dstPage, 0, page.length)", System.class)
            .addStatement("remaining -= page.length")
            .endControlFlow()
            .beginControlFlow("if (remaining > 0)")
            .addStatement("$1T.arraycopy($2N[lastPage], 0, dst.$2N[lastPage], 0, (int) remaining)", System.class, pages)
            .addStatement("$1T.fill(dst.$2N[lastPage], (int) remaining, dst.$2N[lastPage].length, $3L)", Arrays.class, pages, defaultValue)
            .endControlFlow()
            .beginControlFlow("for (int i = pageLen; i < dst.$N.length; i++)", pages)
            .addStatement("$T.fill(dst.pages[i], $L)", Arrays.class, defaultValue)
            .endControlFlow()
            .build();
    }
}
