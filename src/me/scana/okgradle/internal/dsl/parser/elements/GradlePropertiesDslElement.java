/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.scana.okgradle.internal.dsl.parser.elements;

import com.android.annotations.VisibleForTesting;
import me.scana.okgradle.internal.dsl.api.ext.PropertyType;
import me.scana.okgradle.internal.dsl.parser.GradleReferenceInjection;
import me.scana.okgradle.internal.dsl.parser.apply.ApplyDslElement;
import me.scana.okgradle.internal.dsl.parser.elements.ElementState;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslBlockElement;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslElementImpl;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpression;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslLiteral;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslMethodCall;
import me.scana.okgradle.internal.dsl.parser.elements.GradleDslSimpleExpression;
import me.scana.okgradle.internal.dsl.parser.elements.GradleNameElement;
import me.scana.okgradle.internal.dsl.parser.ext.ElementSort;
import me.scana.okgradle.internal.dsl.parser.ext.ExtDslElement;
import me.scana.okgradle.internal.dsl.parser.files.GradleDslFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static me.scana.okgradle.internal.dsl.model.ext.PropertyUtil.isPropertiesElementOrMap;
import static me.scana.okgradle.internal.dsl.model.notifications.NotificationTypeReference.PROPERTY_PLACEMENT;
import static me.scana.okgradle.internal.dsl.parser.elements.ElementState.*;

/**
 * Base class for {@link me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement}s that represent a closure block or a map element. It provides the functionality to store the
 * data as key value pairs and convenient methods to access the data.
 * <p>
 * TODO: Rename this class to something different as this will be conflicting with GradlePropertiesModel
 */
public abstract class GradlePropertiesDslElement extends GradleDslElementImpl {
  @NotNull private final static Predicate<ElementList.ElementItem> VARIABLE_FILTER =
    e -> e.myElement.getElementType() == PropertyType.VARIABLE;
  // This filter currently gives us everything that is not a variable.
  @NotNull private final static Predicate<ElementList.ElementItem> PROPERTY_FILTER = VARIABLE_FILTER.negate();
  @NotNull private final static Predicate<ElementList.ElementItem> ANY_FILTER = e -> true;

  @NotNull private final ElementList myProperties = new ElementList();

  protected GradlePropertiesDslElement(@Nullable me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement parent,
                                       @Nullable PsiElement psiElement,
                                       @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleNameElement name) {
    super(parent, psiElement, name);
  }

  /**
   * Adds the given {@code property}. All additions to {@code myProperties} should be made via this function to
   * ensure that {@code myVariables} is also updated.
   *
   * @param element the {@code GradleDslElement} for the property.
   */
  private void addPropertyInternal(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull me.scana.okgradle.internal.dsl.parser.elements.ElementState state) {
    if (this instanceof ExtDslElement && state == TO_BE_ADDED) {
      int index = reorderAndMaybeGetNewIndex(element);
      myProperties.addElementAtIndex(element, state, index, false);
    }
    else {
      myProperties.addElement(element, state, state == EXISTING);
    }

    if (state == TO_BE_ADDED) {
      updateDependenciesOnAddElement(element);
      element.setModified();
    }
  }

  public void addParsedPropertyAsFirstElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement extElement) {
    myProperties.addElementAtIndex(extElement, EXISTING, 0, true);
  }

  private void addPropertyInternal(int index, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull me.scana.okgradle.internal.dsl.parser.elements.ElementState state) {
    myProperties.addElementAtIndex(element, state, index, state == EXISTING);
    if (state == TO_BE_ADDED) {
      updateDependenciesOnAddElement(element);
      element.setModified();
    }
  }

  private void addAppliedProperty(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    element.addHolder(this);
    addPropertyInternal(element, APPLIED);
  }

  private void removePropertyInternal(@NotNull String property) {
    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> elements = myProperties.removeAll(e -> e.myElement.getName().equals(property));
    elements.forEach(e -> {
      e.setModified();
      updateDependenciesOnRemoveElement(e);
    });
    // Since we only setModified after the child is removed we need to set us to be modified after.
    setModified();
  }

  /**
   * Removes the property by the given element. Returns the OLD ElementState.
   */
  private me.scana.okgradle.internal.dsl.parser.elements.ElementState removePropertyInternal(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    element.setModified();
    me.scana.okgradle.internal.dsl.parser.elements.ElementState state = myProperties.remove(element);
    updateDependenciesOnRemoveElement(element);
    return state;
  }

  private me.scana.okgradle.internal.dsl.parser.elements.ElementState replacePropertyInternal(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement) {
    element.setModified();
    updateDependenciesOnReplaceElement(element, newElement);
    newElement.setModified();

    me.scana.okgradle.internal.dsl.parser.elements.ElementState oldState = myProperties.replaceElement(element, newElement);
    reorderAndMaybeGetNewIndex(newElement);
    return oldState;
  }

  private void hidePropertyInternal(@NotNull String property) {
    myProperties.hideAll(e -> e.myElement.getName().equals(property));
  }

  public void addAppliedModelProperties(@NotNull GradleDslFile file) {
    // Here we need to merge the properties into from the applied file into this element.
    mergePropertiesFrom(file);
  }

  private void mergePropertiesFrom(@NotNull GradlePropertiesDslElement other) {
    Map<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> ourProperties = getPropertyElements();
    for (Map.Entry<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> entry : other.getPropertyElements().entrySet()) {
      me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newProperty = entry.getValue();

      // Don't merge ApplyDslElements, this can cause stack overflow exceptions while applying changes in
      // complex projects.
      if (newProperty instanceof ApplyDslElement) {
        continue;
      }

      if (ourProperties.containsKey(entry.getKey())) {
        me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement existingProperty = getElementWhere(entry.getKey(), PROPERTY_FILTER);
        // If they are both block elements, merge them.
        if (newProperty instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslBlockElement && existingProperty instanceof GradleDslBlockElement) {
          ((GradlePropertiesDslElement)existingProperty).mergePropertiesFrom((GradlePropertiesDslElement)newProperty);
          continue;
        }
      }
      else if (isPropertiesElementOrMap(newProperty)) {
        // If the element we are trying to add a GradlePropertiesDslElement that doesn't exist, create it.
        GradlePropertiesDslElement createdElement =
          getDslFile().getParser().getBlockElement(Arrays.asList(entry.getKey().split("\\.")), this, null);
        if (createdElement != null) {
          // Merge it with the created element.
          createdElement.mergePropertiesFrom((GradlePropertiesDslElement)newProperty);
          continue;
        }
      }

      // Otherwise just add the new property.
      addAppliedProperty(entry.getValue());
    }
  }

  /**
   * Sets or replaces the given {@code property} value with the give {@code element}.
   *
   * <p>This method should be used when the given {@code property} is defined using an assigned statement.
   */
  public void setParsedElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    element.setParent(this);
    addPropertyInternal(element, EXISTING);
  }

  /**
   * Sets or replaces the given {@code property} value with the given {@code element}.
   *
   * <p>This method should be used when the given {@code property} is defined using an application statement. As the application statements
   * can have different meanings like append vs replace for list elements, the sub classes can override this method to do the right thing
   * for any given property.
   */
  public void addParsedElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    element.setParent(this);
    addPropertyInternal(element, EXISTING);
  }

  /**
   * Sets or replaces the given {@code property} value with the given {@code element}.
   *
   * <p>This method should be used when the given {@code property} would reset the effect of the other property. Ex: {@code reset()} method
   * in android.splits.abi block will reset the effect of the previously defined {@code includes} element.
   */
  protected void addParsedResettingElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull String propertyToReset) {
    element.setParent(this);
    addPropertyInternal(element, EXISTING);
    hidePropertyInternal(propertyToReset);
  }

  protected void addAsParsedDslExpressionList(me.scana.okgradle.internal.dsl.parser.elements.GradleDslSimpleExpression expression) {
    PsiElement psiElement = expression.getPsiElement();
    if (psiElement == null) {
      return;
    }
    // Only elements which are added as expression list are the ones which supports both single argument and multiple arguments
    // (ex: flavorDimensions in android block). To support that, we create an expression list where appending to the arguments list is
    // supported even when there is only one element in it. This does not work in many other places like proguardFile elements where
    // only one argument is supported and for this cases we use addToParsedExpressionList method.
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList literalList =
      new me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList(this, psiElement, me.scana.okgradle.internal.dsl.parser.elements.GradleNameElement.create(expression.getName()), true);
    if (expression instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslMethodCall) {
      // Make sure the psi is set to the argument list instead of the whole method call.
      literalList.setPsiElement(((me.scana.okgradle.internal.dsl.parser.elements.GradleDslMethodCall)expression).getArgumentListPsiElement());
      for (me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element : ((me.scana.okgradle.internal.dsl.parser.elements.GradleDslMethodCall)expression).getArguments()) {
        if (element instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslSimpleExpression) {
          literalList.addParsedExpression((me.scana.okgradle.internal.dsl.parser.elements.GradleDslSimpleExpression)element);
        }
      }
    }
    else {
      literalList.addParsedExpression(expression);
    }
    addPropertyInternal(literalList, EXISTING);
  }

  public void addToParsedExpressionList(@NotNull String property, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    if (element.getPsiElement() == null) {
      return;
    }

    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> newElements = new ArrayList<>();
    PsiElement psiElement = element.getPsiElement();
    if (element instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslMethodCall) {
      List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpression> args = ((GradleDslMethodCall)element).getArguments();
      if (!args.isEmpty()) {
        if (args.size() == 1 && args.get(0) instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList) {
          newElements.addAll(((me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList)args.get(0)).getExpressions());
          PsiElement newElement = args.get(0).getPsiElement();
          psiElement = newElement != null ? newElement : psiElement;
        }
        else {
          newElements.addAll(args);
        }
      }
    }
    else if (element instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslSimpleExpression) {
      newElements.add(element);
    }
    else if (element instanceof me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList) {
      newElements.addAll(((me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList)element).getExpressions());
    }

    me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList gradleDslExpressionList = getPropertyElement(property, me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList.class);
    if (gradleDslExpressionList == null) {
      gradleDslExpressionList = new me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList(this, psiElement, me.scana.okgradle.internal.dsl.parser.elements.GradleNameElement.create(property), false);
      addPropertyInternal(gradleDslExpressionList, EXISTING);
    }
    else {
      gradleDslExpressionList.setPsiElement(psiElement);
    }
    newElements.forEach(gradleDslExpressionList::addParsedElement);
  }

  @NotNull
  public Set<String> getProperties() {
    return getPropertyElements().keySet();
  }

  /**
   * Note: This function does NOT guarantee that only elements belonging to properties are returned, since this class is also used
   * for maps it is also possible for the resulting elements to be of {@link PropertyType#DERIVED}.
   */
  @NotNull
  public Map<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getPropertyElements() {
    return getElementsWhere(PROPERTY_FILTER);
  }

  @NotNull
  public <T extends me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> List<T> getPropertyElements(@NotNull String name, @NotNull Class<T> clazz) {
    return myProperties.getElementsWhere(PROPERTY_FILTER).stream()
                       .filter(e -> clazz.isAssignableFrom(e.getClass()) && e.getName().equals(name))
                       .map(e -> clazz.cast(e)).collect(Collectors.toList());
  }

  @NotNull
  public List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getAllPropertyElements() {
    return myProperties.getElementsWhere(PROPERTY_FILTER);
  }

  @NotNull
  public Map<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getVariableElements() {
    return getElementsWhere(VARIABLE_FILTER);
  }

  @NotNull
  public Map<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getElements() {
    return getElementsWhere(ANY_FILTER);
  }

  @NotNull
  public List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getAllElements() {
    return myProperties.getElementsWhere(ANY_FILTER);
  }

  @NotNull
  private Map<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getElementsWhere(@NotNull Predicate<ElementList.ElementItem> predicate) {
    Map<String, me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> results = new LinkedHashMap<>();
    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> elements = myProperties.getElementsWhere(predicate);
    for (me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element : elements) {
      if (element != null) {
        results.put(element.getName(), element);
      }
    }
    return results;
  }

  private me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getElementWhere(@NotNull String name, @NotNull Predicate<ElementList.ElementItem> predicate) {
    return getElementsWhere(predicate).get(name);
  }

  @Nullable
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getVariableElement(@NotNull String property) {
    return getElementWhere(property, VARIABLE_FILTER);
  }

  /**
   * Returns the {@link me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement} corresponding to the given {@code property}, or {@code null} if the given {@code property}
   * does not exist in this element.
   */
  @Nullable
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getPropertyElement(@NotNull String property) {
    return getElementWhere(property, PROPERTY_FILTER);
  }

  @Nullable
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getElement(@NotNull String property) {
    return getElementWhere(property, ANY_FILTER);
  }

  @Nullable
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getPropertyElementBefore(@Nullable me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull String property, boolean includeSelf) {
    if (element == null) {
      return getElementWhere(property, PROPERTY_FILTER);
    }
    else {
      return myProperties
        .getElementBeforeChildWhere(e -> PROPERTY_FILTER.test(e) && e.myElement.getName().equals(property), element, includeSelf);
    }
  }

  @Nullable
  me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getElementBefore(@Nullable me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull String property, boolean includeSelf) {
    if (element == null) {
      return getElementWhere(property, ANY_FILTER);
    }
    else {
      return myProperties
        .getElementBeforeChildWhere(e -> ANY_FILTER.test(e) && e.myElement.getName().equals(property), element, includeSelf);
    }
  }

  /**
   * Returns the dsl element of the given {@code property} of the type {@code clazz}, or {@code null} if the given {@code property}
   * does not exist in this element.
   */
  @Nullable
  public <T extends me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> T getPropertyElement(@NotNull String property, @NotNull Class<T> clazz) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement propertyElement = getPropertyElement(property);
    return clazz.isInstance(propertyElement) ? clazz.cast(propertyElement) : null;
  }

  @Nullable
  public <T extends me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> T getPropertyElement(@NotNull List<String> properties, @NotNull Class<T> clazz) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement propertyElement = myProperties.getElementWhere(e -> properties.contains(e.myElement.getName()));
    return clazz.isInstance(propertyElement) ? clazz.cast(propertyElement) : null;
  }

  @NotNull
  public <T extends me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> List<T> getPropertyElements(@NotNull Class<T> clazz) {
    return myProperties.getElementsWhere(PROPERTY_FILTER).stream().filter(e -> clazz.isAssignableFrom(e.getClass())).map(e -> clazz.cast(e))
                       .collect(Collectors.toList());
  }

  @NotNull
  public List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getPropertyElementsByName(@NotNull String propertyName) {
    return myProperties.getElementsWhere(e -> e.myElement.getName().equals(propertyName) && PROPERTY_FILTER.test(e));
  }

  @NotNull
  public List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getOriginalElements() {
    return myProperties.myElements.stream().filter(e -> e.myExistsOnFile).map(e -> e.myElement).collect(Collectors.toList());
  }

  @Nullable
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getOriginalElementForNameAndType(@NotNull String name, @NotNull PropertyType type) {
    return myProperties.myElements.stream().filter(
      e -> e.myElement.getName().equals(name) && e.myExistsOnFile && e.myElement.getElementType() == type).map(e -> e.myElement)
                                  .reduce((a, b) -> b).orElse(null);
  }

  /**
   * @return all the current elements with the state TO_BE_ADDED or EXISTING.
   */
  @NotNull
  public List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getCurrentElements() {
    return myProperties.myElements.stream().filter(e -> e.myElementState == TO_BE_ADDED || e.myElementState == EXISTING)
                                  .map(e -> e.myElement).collect(Collectors.toList());
  }

  /**
   * Adds the given element to the to-be added elements list, which are applied when {@link #apply()} method is invoked
   * or discarded when the {@lik #resetState()} method is invoked.
   */
  @NotNull
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement setNewElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement) {
    newElement.setParent(this);
    addPropertyInternal(newElement, TO_BE_ADDED);
    setModified();
    return newElement;
  }

  public void addNewElementAt(int index, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement) {
    newElement.setParent(this);
    addPropertyInternal(index, newElement, TO_BE_ADDED);
    setModified();
  }

  public <T> void addNewElementBeforeAllOfClass(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement, @NotNull Class<T> clazz) {
    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> elements = getAllElements();
    int index = elements.size() - 1;
    for (int i = 0; i < elements.size() - 1; i++) {
      if (clazz.isInstance(elements.get(i))) {
        index = i;
      }
    }
    addNewElementAt(index, newElement);
  }

  @VisibleForTesting
  public void moveElementTo(int index, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement) {
    assert newElement.getParent() == this;
    myProperties.moveElementToIndex(newElement, index);
  }

  @NotNull
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement replaceElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement oldElement, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement) {
    newElement.setParent(this);
    List<GradlePropertiesDslElement> holders = new ArrayList<>();
    holders.add(this);
    holders.addAll(oldElement.getHolders());
    for (GradlePropertiesDslElement holder : holders) {
      holder.replacePropertyInternal(oldElement, newElement);
    }
    return newElement;
  }

  @Nullable
  public <T> T getLiteral(@NotNull String property, @NotNull Class<T> clazz) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslSimpleExpression expression = getPropertyElement(property, GradleDslSimpleExpression.class);
    if (expression == null) {
      return null;
    }

    return expression.getValue(clazz);
  }

  @NotNull
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement setNewLiteral(@NotNull String property, @NotNull Object value) {
    return setNewLiteralImpl(property, value);
  }

  @NotNull
  private me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement setNewLiteralImpl(@NotNull String property, @NotNull Object value) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslLiteral literalElement = getPropertyElement(property, me.scana.okgradle.internal.dsl.parser.elements.GradleDslLiteral.class);
    if (literalElement == null) {
      literalElement = new GradleDslLiteral(this, me.scana.okgradle.internal.dsl.parser.elements.GradleNameElement.create(property));
      addPropertyInternal(literalElement, TO_BE_ADDED);
    }
    literalElement.setValue(value);
    return literalElement;
  }

  @NotNull
  public GradlePropertiesDslElement addToNewLiteralList(@NotNull String property, @NotNull String value) {
    return addToNewLiteralListImpl(property, value);
  }

  @NotNull
  private GradlePropertiesDslElement addToNewLiteralListImpl(@NotNull String property, @NotNull Object value) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList gradleDslExpressionList = getPropertyElement(property, me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList.class);
    if (gradleDslExpressionList == null) {
      gradleDslExpressionList = new me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList(this, GradleNameElement.create(property), false);
      addPropertyInternal(gradleDslExpressionList, TO_BE_ADDED);
    }
    gradleDslExpressionList.addNewLiteral(value);
    return this;
  }

  @NotNull
  public GradlePropertiesDslElement removeFromExpressionList(@NotNull String property, @NotNull String value) {
    return removeFromExpressionListImpl(property, value);
  }

  @NotNull
  private GradlePropertiesDslElement removeFromExpressionListImpl(@NotNull String property, @NotNull Object value) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList gradleDslExpressionList = getPropertyElement(property, me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList.class);
    if (gradleDslExpressionList != null) {
      gradleDslExpressionList.removeExpression(value);
    }
    return this;
  }

  @NotNull
  public GradlePropertiesDslElement replaceInExpressionList(@NotNull String property, @NotNull String oldValue, @NotNull String newValue) {
    return replaceInExpressionListImpl(property, oldValue, newValue);
  }

  @NotNull
  private GradlePropertiesDslElement replaceInExpressionListImpl(@NotNull String property,
                                                                 @NotNull Object oldValue,
                                                                 @NotNull Object newValue) {
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslExpressionList gradleDslExpressionList = getPropertyElement(property, GradleDslExpressionList.class);
    if (gradleDslExpressionList != null) {
      gradleDslExpressionList.replaceExpression(oldValue, newValue);
    }
    return this;
  }

  /**
   * Marks the given {@code property} for removal.
   *
   * <p>The actual property will be removed from Gradle file when {@link #apply()} method is invoked.
   *
   * <p>The property will be un-marked for removal when {@link #reset()} method is invoked.
   */
  public void removeProperty(@NotNull String property) {
    removePropertyInternal(property);
  }

  public void removeProperty(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    removePropertyInternal(element);
  }

  @Override
  @Nullable
  public me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement requestAnchor(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    // We need to find the element before `element` in my properties. The last one that has a psiElement, has the same name scheme as
    // the given element (to ensure that they should be placed in the same block) and much either have a state of TO_BE_ADDED or EXISTING.
    me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement lastElement = null;
    for (ElementList.ElementItem item : myProperties.myElements) {
      if (item.myElement == element) {
        return lastElement;
      }

      if (item.myElementState != TO_BE_REMOVED && item.myElementState != HIDDEN && item.myElementState != APPLIED &&
          item.myElement.getNameElement().qualifyingParts().equals(element.getNameElement().qualifyingParts())) {
        if (item.myElement instanceof ApplyDslElement) {
          lastElement = item.myElement.requestAnchor(element);
        }
        else {
          lastElement = item.myElement;
        }
      }
    }

    // The element is not in this list, we can't provide an anchor. Default to adding it at the end.
    return lastElement;
  }

  @Override
  @NotNull
  public Collection<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getChildren() {
    return getAllElements();
  }

  @Override
  @NotNull
  public List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getContainedElements(boolean includeProperties) {
    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> result = new ArrayList<>();
    if (includeProperties) {
      result.addAll(getElementsWhere(e -> e.myElementState != APPLIED).values());
    }
    else {
      result.addAll(getVariableElements().values());
    }

    // We don't want to include lists and maps in this.
    List<GradlePropertiesDslElement> holders =
      getPropertyElements(GradlePropertiesDslElement.class).stream().filter(e -> !(e instanceof GradleDslExpression))
                                                           .collect(Collectors.toList());

    holders.forEach(e -> result.addAll(e.getContainedElements(includeProperties)));
    return result;
  }

  @Override
  protected void apply() {
    getDslFile().getWriter().applyDslPropertiesElement(this);
    myProperties.removeElements(me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement::delete);
    myProperties.createElements((e) -> e.create() != null);
    myProperties.applyElements(e -> {
      if (e.isModified()) {
        e.applyChanges();
      }
    });
    myProperties.forEach(item -> {
      if (item.myElementState == MOVED) {
        item.myElement.move();
      }
    });
  }

  @Override
  protected void reset() {
    myProperties.reset();
  }

  protected void clear() {
    myProperties.clear();
  }

  public int reorderAndMaybeGetNewIndex(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    int result = sortElementsAndMaybeGetNewIndex(element);
    element.resolve();
    return result;
  }

  private int sortElementsAndMaybeGetNewIndex(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> currentElements =
      myProperties.getElementsWhere(e -> e.myElementState == EXISTING || e.myElementState == TO_BE_ADDED);
    List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> sortedElements = new ArrayList<>();
    boolean result = ElementSort.create(this, element).sort(currentElements, sortedElements);
    int resultIndex = myProperties.myElements.size();

    if (!result) {
      notification(PROPERTY_PLACEMENT);
      return resultIndex;
    }

    int i = 0, j = 0;
    while (i < currentElements.size() && j < sortedElements.size()) {
      if (currentElements.get(i) == sortedElements.get(i)) {
        i++;
        j++;
        continue;
      }

      if (sortedElements.get(i) == element && !currentElements.contains(element)) {
        resultIndex = i;
        j++;
        continue;
      }

      // Move the element into the correct position.
      moveElementTo(i, sortedElements.get(j));
      i++;
      j++;
    }

    return resultIndex;
  }

  @Override
  @NotNull
  public List<GradleReferenceInjection> getDependencies() {
    return myProperties.getElementsWhere(e -> e.myElementState != APPLIED).stream().map(me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement::getDependencies)
                       .flatMap(Collection::stream).collect(
        Collectors.toList());
  }

  @VisibleForTesting
  public boolean isApplied(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
    for (ElementList.ElementItem item : myProperties.myElements) {
      if (item.myElement == element) {
        return item.myElementState == APPLIED;
      }
    }
    // The element must be found.
    throw new IllegalStateException("Element not found in parent");
  }

  /**
   * Class to deal with retrieving the correct property for a given context. It manages whether
   * or not variable types should be returned along with coordinating a number of properties
   * with the same name.
   */
  private static class ElementList {
    /**
     * Wrapper to add state to each element.
     */
    private static class ElementItem {
      @NotNull private me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement myElement;
      @NotNull private me.scana.okgradle.internal.dsl.parser.elements.ElementState myElementState;
      // Whether or not this element item exists in THIS DSL file. While element state == EXISTING implies this is true,
      // the reserve doesn't apply.
      private boolean myExistsOnFile;

      private ElementItem(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, @NotNull me.scana.okgradle.internal.dsl.parser.elements.ElementState state, boolean existsOnFile) {
        myElement = element;
        myElementState = state;
        myExistsOnFile = existsOnFile;
      }
    }

    @NotNull private final List<ElementItem> myElements;

    private ElementList() {
      myElements = new ArrayList<>();
    }

    private void forEach(@NotNull Consumer<ElementItem> func) {
      myElements.forEach(func);
    }

    @NotNull
    private List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> getElementsWhere(@NotNull Predicate<ElementItem> predicate) {
      return myElements.stream().filter(e -> e.myElementState != TO_BE_REMOVED && e.myElementState != HIDDEN)
                       .filter(predicate).map(e -> e.myElement).collect(Collectors.toList());
    }

    @Nullable
    private me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getElementWhere(@NotNull Predicate<ElementItem> predicate) {
      // We reduce to get the last element stored, this will be the one we want as it was added last and therefore must appear
      // later on in the file.
      return myElements.stream().filter(e -> e.myElementState != TO_BE_REMOVED && e.myElementState != HIDDEN)
                       .filter(predicate).map(e -> e.myElement).reduce((first, second) -> second).orElse(null);
    }

    /**
     * Return the last element satisfying {@code predicate} that is BEFORE {@code child}. If {@code child} is not a child of
     * this {@link GradlePropertiesDslElement} then every element is checked and the last one (if any) returned.
     */
    @Nullable
    private me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement getElementBeforeChildWhere(@NotNull Predicate<ElementItem> predicate,
                                                                                                          @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement child,
                                                                                                          boolean includeSelf) {
      me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement lastElement = null;
      for (ElementItem i : myElements) {
        // Skip removed or hidden elements.
        if (i.myElementState == TO_BE_REMOVED || i.myElementState == HIDDEN) {
          continue;
        }

        if (predicate.test(i)) {
          if (includeSelf || child != i.myElement) {
            lastElement = i.myElement;
          }
        }

        if (i.myElement == child) {
          return lastElement;
        }
      }
      return lastElement;
    }

    private void addElement(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement, @NotNull me.scana.okgradle.internal.dsl.parser.elements.ElementState state, boolean onFile) {
      myElements.add(new ElementItem(newElement, state, onFile));
    }

    private void addElementAtIndex(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement, @NotNull me.scana.okgradle.internal.dsl.parser.elements.ElementState state, int index, boolean onFile) {
      myElements.add(getRealIndex(index, newElement), new ElementItem(newElement, state, onFile));
    }

    // Note: The index position is calculated AFTER the element has been removed from the list.
    private void moveElementToIndex(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element, int index) {
      // Find the element.
      ElementItem item = myElements.stream().filter(e -> e.myElement == element).findFirst().orElse(null);
      if (item == null) {
        return;
      }

      // Remove the element.
      myElements.remove(item);
      // Set every EXISTING element in this tree to MOVED.
      moveElementTree(item);
      // Add the element back at the given index.
      myElements.add(getRealIndex(index, element), item);
    }

    /**
     * Converts a given index to a real index that can correctly place elements in myElements. This ignores all elements that should be
     * removed or have been applied.
     */
    private int getRealIndex(int index, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
      // If the index is less than zero then clamp it to zero
      if (index <= 0) {
        return 0;
      }

      // Work out the real index
      for (int i = 0; i < myElements.size(); i++) {
        if (index == 0) {
          return i;
        }
        ElementItem item = myElements.get(i);
        if (item.myElementState != TO_BE_REMOVED &&
            item.myElementState != APPLIED &&
            item.myElementState != HIDDEN) {
          index--;
        }
      }
      return myElements.size();
    }

    @Nullable
    private me.scana.okgradle.internal.dsl.parser.elements.ElementState remove(@NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement element) {
      ElementItem item = myElements.stream().filter(e -> element == e.myElement).findFirst().orElse(null);
      if (item == null) {
        return null;
      }
      me.scana.okgradle.internal.dsl.parser.elements.ElementState oldState = item.myElementState;
      item.myElementState = TO_BE_REMOVED;
      return oldState;
    }

    @Nullable
    private me.scana.okgradle.internal.dsl.parser.elements.ElementState replaceElement(@Nullable me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement oldElement, @NotNull me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement newElement) {
      for (int i = 0; i < myElements.size(); i++) {
        ElementItem item = myElements.get(i);
        if (oldElement == item.myElement) {
          me.scana.okgradle.internal.dsl.parser.elements.ElementState oldState = item.myElementState;
          item.myElementState = TO_BE_REMOVED;
          me.scana.okgradle.internal.dsl.parser.elements.ElementState newState = TO_BE_ADDED;
          if (oldState == APPLIED || oldState == HIDDEN) {
            newState = oldState;
          }
          myElements.add(i, new ElementItem(newElement, newState, false));
          return oldState;
        }
      }
      return null;
    }

    @NotNull
    private List<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> removeAll(@NotNull Predicate<ElementItem> filter) {
      List<ElementItem> toBeRemoved = myElements.stream().filter(filter).collect(Collectors.toList());
      toBeRemoved.forEach(e -> e.myElementState = TO_BE_REMOVED);
      return toBeRemoved.stream().map(e -> e.myElement).collect(Collectors.toList());
    }

    private void hideAll(@NotNull Predicate<ElementItem> filter) {
      myElements.stream().filter(filter).forEach(e -> e.myElementState = HIDDEN);
    }

    private boolean isEmpty() {
      return myElements.isEmpty();
    }

    private void reset() {
      for (Iterator<ElementItem> i = myElements.iterator(); i.hasNext(); ) {
        ElementItem item = i.next();
        item.myElement.resetState();
        if (item.myElementState == TO_BE_REMOVED) {
          item.myElementState = EXISTING;
        }
        if (item.myElementState == TO_BE_ADDED) {
          i.remove();
        }
      }
    }

    /**
     * Runs {@code removeFunc} across all of the elements with {@link me.scana.okgradle.internal.dsl.parser.elements.ElementState#TO_BE_REMOVED} stored in this list.
     * Once {@code removeFunc} has been run, the element is removed from the list.
     */
    private void removeElements(@NotNull Consumer<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> removeFunc) {
      for (Iterator<ElementItem> i = myElements.iterator(); i.hasNext(); ) {
        ElementItem item = i.next();
        if (item.myElementState == TO_BE_REMOVED) {
          removeFunc.accept(item.myElement);
          i.remove();
        }
      }
    }

    /**
     * Runs {@code addFunc} across all of the elements with {@link me.scana.okgradle.internal.dsl.parser.elements.ElementState#TO_BE_ADDED} stored in this list.
     * If {@code addFunc} returns true then the state is changed to {@link ElementState#EXISTING} else the element
     * is removed.
     */
    private void createElements(@NotNull Predicate<me.scana.okgradle.internal.dsl.parser.elements.GradleDslElement> addFunc) {
      for (Iterator<ElementItem> i = myElements.iterator(); i.hasNext(); ) {
        ElementItem item = i.next();
        if (item.myElementState == TO_BE_ADDED) {
          if (addFunc.test(item.myElement)) {
            item.myElementState = EXISTING;
          }
          else {
            i.remove();
          }
        }
      }
    }

    /**
     * Runs {@code func} across all of the elements stored in this list.
     */
    private void applyElements(@NotNull Consumer<GradleDslElement> func) {
      myElements.stream().filter(e -> e.myElementState != APPLIED).map(e -> e.myElement).forEach(func);
    }

    /**
     * Clears ALL element in this element list. This clears the whole list without affecting state. If you actually want to remove
     * elements from the file use {@link #removeAll(Predicate)}.
     */
    private void clear() {
      myElements.clear();
    }

    /**
     * Moves the element tree represented by item.
     *
     * @param item root of the tree to be movedx
     */
    private static void moveElementTree(@NotNull ElementItem item) {
      // Move the current element item, unless it is not on file yet.
      if (item.myElementState != TO_BE_ADDED) {
        item.myElementState = MOVED;
      }
      // Mark it as modified.
      item.myElement.setModified();
    }
  }
}
