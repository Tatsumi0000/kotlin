/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.components

import org.jetbrains.kotlin.resolve.calls.inference.components.KotlinConstraintSystemCompleter
import org.jetbrains.kotlin.resolve.calls.inference.components.KotlinConstraintSystemCompleter.ConstraintSystemCompletionMode
import org.jetbrains.kotlin.resolve.calls.inference.model.Constraint
import org.jetbrains.kotlin.resolve.calls.inference.model.VariableWithConstraints
import org.jetbrains.kotlin.resolve.calls.model.KotlinResolutionCandidate
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.model.*
import java.util.*

class CompletionModeCalculator(
    private val candidate: KotlinResolutionCandidate,
    private val expectedType: UnwrappedType?,
    private val returnType: UnwrappedType?
) {
    private val csCompleterContext: KotlinConstraintSystemCompleter.Context =
        candidate.getSystem().asConstraintSystemCompleterContext()

    private enum class FixationRequirement {
        LOWER, UPPER, EQUALITY
    }

    private val fixationRequirementsForVariables: MutableMap<VariableWithConstraints, FixationRequirement> = mutableMapOf()
    private val variablesWithQueuedConstraints: MutableSet<TypeVariableMarker> = mutableSetOf()
    private val typesToProcess: Queue<KotlinTypeMarker> = ArrayDeque()

    fun computeCompletionMode(): ConstraintSystemCompletionMode = with(candidate) {
        // Presence of expected type means that we are trying to complete outermost call => completion mode should be full
        if (expectedType != null) return ConstraintSystemCompletionMode.FULL

        // This is questionable as null return type can be only for error call
        if (returnType == null) return ConstraintSystemCompletionMode.PARTIAL

        // Return type for call has no type variables
        if (csBuilder.isProperType(returnType)) return ConstraintSystemCompletionMode.FULL

        // Add fixation directions for variables based on effective variance in type
        typesToProcess.add(returnType)
        val requirementStatus = makeRequirements()
        if (requirementStatus == RequirementStatus.CONTRADICTION)
            return ConstraintSystemCompletionMode.PARTIAL

        // If there is no contradiction and all variables have required proper constraint, run full completion
        if (requirementsForVariablesSatisfied())
            return ConstraintSystemCompletionMode.FULL

        return ConstraintSystemCompletionMode.PARTIAL
    }

    private enum class RequirementStatus { OK, CONTRADICTION }

    private fun makeRequirements(): RequirementStatus = with(csCompleterContext) {
        while (typesToProcess.isNotEmpty()) {
            val type = typesToProcess.poll() ?: break

            fixationRequirementForTopLevel(type)?.let {
                if (processRequirementAndCheckContradiction(it))
                    return RequirementStatus.CONTRADICTION
            }

            // find all variables in type and make requirements for them
            val hasContradiction = type.contains { fromReturnType ->
                for (requirementForVariable in fixationRequirementsForArguments(fromReturnType)) {
                    if (processRequirementAndCheckContradiction(requirementForVariable))
                        return@contains true
                }
                false
            }

            if (hasContradiction)
                return RequirementStatus.CONTRADICTION
        }

        return RequirementStatus.OK
    }

    // true for contradiction to stop contains
    private fun processRequirementAndCheckContradiction(requirement: FixationRequirementForVariable): Boolean {
        val (variableWithConstraints, fixationRequirement) = requirement
        val variable = variableWithConstraints.typeVariable

        if (!updateDirection(variableWithConstraints, fixationRequirement))
            return true

        if (variable !in variablesWithQueuedConstraints) {
            for (constraint in variableWithConstraints.constraints) {
                typesToProcess.add(constraint.type)
            }

            variablesWithQueuedConstraints.add(variable)
        }
        return false
    }

    private fun requirementsForVariablesSatisfied(): Boolean {
        for ((variable, fixationRequirement) in fixationRequirementsForVariables) {
            if (!hasProperConstraint(variable, fixationRequirement))
                return false
        }
        return true
    }

    private fun updateDirection(variable: VariableWithConstraints, requirement: FixationRequirement): Boolean {
        fixationRequirementsForVariables[variable]?.let {
            return it == requirement
        }
        fixationRequirementsForVariables[variable] = requirement
        return true
    }

    // need something more suitable to avoid map.get()!!
    private val varianceToDirection = mapOf(
        TypeVariance.IN to FixationRequirement.UPPER,
        TypeVariance.OUT to FixationRequirement.LOWER,
        TypeVariance.INV to FixationRequirement.EQUALITY
    )

    private data class FixationRequirementForVariable(val variable: VariableWithConstraints, val requirement: FixationRequirement)

    private fun fixationRequirementForTopLevel(type: KotlinTypeMarker): FixationRequirementForVariable? =
        with(csCompleterContext) {
            return notFixedTypeVariables[type.typeConstructor()]?.let {
                FixationRequirementForVariable(it, FixationRequirement.LOWER)
            }
        }

    private fun fixationRequirementsForArguments(type: KotlinTypeMarker): List<FixationRequirementForVariable> =
        with(csCompleterContext) {
            val requirementsFromType = mutableListOf<FixationRequirementForVariable>()
            assert(type.argumentsCount() == type.typeConstructor().parametersCount()) {
                "Argument and parameter count don't match for type $type. " +
                        "Arguments: ${type.argumentsCount()}, parameters: ${type.typeConstructor().parametersCount()}"
            }

            for (position in 0 until type.argumentsCount()) {
                val argument = type.getArgument(position)
                if (!argument.getType().mayBeTypeVariable())
                    continue

                val variableWithConstraints = notFixedTypeVariables[argument.getType().typeConstructor()] ?: continue

                val parameter = type.typeConstructor().getParameter(position)
                val effectiveVariance = AbstractTypeChecker.effectiveVariance(parameter.getVariance(), argument.getVariance())
                    ?: TypeVariance.OUT // Discuss

                val requirement = FixationRequirementForVariable(variableWithConstraints, varianceToDirection[effectiveVariance]!!)
                requirementsFromType.add(requirement)
            }

            return requirementsFromType
        }

    private fun hasProperConstraint(variableWithConstraints: VariableWithConstraints, requirement: FixationRequirement): Boolean =
        with(csCompleterContext) {
            val constraints = variableWithConstraints.constraints

            // todo check correctness for @Exact
            constraints.isNotEmpty() && constraints.any { constraint ->
                constraint.hasRequiredKind(requirement)
                        && !constraint.type.typeConstructor().isIntegerLiteralTypeConstructor()
                        && isProperType(constraint.type)
            }
        }

    private fun Constraint.hasRequiredKind(requirement: FixationRequirement) = when (requirement) {
        FixationRequirement.LOWER -> kind.isLower() || kind.isEqual()
        FixationRequirement.UPPER -> kind.isUpper() || kind.isEqual()
        FixationRequirement.EQUALITY -> kind.isEqual()
    }
}