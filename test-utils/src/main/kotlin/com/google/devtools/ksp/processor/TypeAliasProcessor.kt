/*
 * Copyright 2020 Google LLC
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.ksp.processor

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*

open class TypeAliasProcessor : AbstractTestProcessor() {
    val results = mutableListOf<String>()
    val types = mutableListOf<KSType>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val byFinalSignature = mutableMapOf<String, MutableList<KSType>>()
        resolver.getNewFiles().flatMap { file ->
            file.declarations.filterIsInstance<KSPropertyDeclaration>().map { prop ->
                buildString {
                    append(prop.simpleName.asString())
                    append(" : ")
                    val propType = prop.type.resolve()
                    val signatures = propType.typeAliasSignatures()
                    append(signatures.joinToString(" = "))
                    byFinalSignature.getOrPut(signatures.last()) {
                        mutableListOf()
                    }.add(propType)
                    append(" = (expanded) ${resolver.expandType(propType).toSignature()}")
                }
            }
        }.forEach(results::add)
        byFinalSignature.forEach { (signature, sameTypeAliases) ->
            // exclude List<T> case from the test because they lose a type argument when resolving aliases, so they
            // are not the same anymore as we traverse the declarations.
            if (signature != "List<T>") {
                for (i in sameTypeAliases) {
                    for (j in sameTypeAliases) {
                        assert(i == j) {
                            "$i and $j both map to $signature, equals should return true"
                        }
                    }
                }
                assert(sameTypeAliases.map { it.hashCode() }.distinct().size == 1) {
                    "different hashcodes for members of $signature"
                }
            }
        }
        return emptyList()
    }

    private fun KSType.typeAliasSignatures(): List<String> {
        var self: KSType? = this
        return buildList {
            while (self != null) {
                add(self!!.toSignature())
                self = (self?.declaration as? KSTypeAlias)?.type?.resolve()
            }
        }
    }

    private fun KSType.toSignature(): String = buildString {
        annotations.toList().let {
            if (it.isNotEmpty()) {
                append(annotations.joinToString(separator = " ", postfix = " ") { "@${it.shortName.asString()}" })
            }
        }
        append(declaration.simpleName.asString())
        if (arguments.isNotEmpty()) {
            append("<")
            arguments.mapIndexed { i, arg ->
                val s = arg.type?.resolve()?.toSignature() ?: "<error>"
                if (i < arguments.size - 1)
                    s + ", "
                else
                    s
            }.forEach(this::append)
            append(">")
        }
    }

    private fun Resolver.expandType(type: KSType, substitutions: MutableMap<KSTypeParameter, KSType>): KSType {
        val decl = type.declaration
        return when (decl) {
            is KSClassDeclaration -> {
                val arguments = type.arguments.map {
                    val argType = it.type?.resolve() ?: return@map it
                    getTypeArgument(createKSTypeReferenceFromKSType(expandType(argType, substitutions)), it.variance)
                }
                decl.asType(arguments)
            }

            is KSTypeParameter -> {
                val substituted = substitutions.get(decl) ?: return type
                val fullySubstituted = expandType(substituted, substitutions)
                // update/cache with refined substitution
                if (substituted != fullySubstituted)
                    substitutions[decl] = fullySubstituted
                fullySubstituted
            }

            is KSTypeAlias -> {
                val aliasedType = decl.type.resolve()

                decl.typeParameters.zip(type.arguments).forEach { (param, arg) ->
                    arg.type?.resolve()?.let {
                        substitutions[param] = it
                    }
                }

                expandType(aliasedType, substitutions)
            }

            else -> type
        }
    }

    private fun Resolver.expandType(type: KSType): KSType {
        return expandType(type, mutableMapOf())
    }

    override fun toResult(): List<String> {
        return results
    }
}
