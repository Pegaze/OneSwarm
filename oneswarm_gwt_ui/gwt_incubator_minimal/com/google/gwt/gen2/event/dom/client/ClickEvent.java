/*
 * Copyright 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.gen2.event.dom.client;

import com.google.gwt.user.client.Event;

/**
 * Represents a native click event.
 * 
 * @deprecated use the com.google.gwt.event.dom.client classes instead
 */
@Deprecated
public class ClickEvent extends DomEvent {

  /**
   * Event type for click events. Represents the meta-data associated with this
   * event.
   */
  public static final Type<ClickEvent, ClickHandler> TYPE = new Type<ClickEvent, ClickHandler>(
      Event.ONCLICK) {
    @Override
    public void fire(ClickHandler handler, ClickEvent event) {
      handler.onClick(event);
    }

    @Override
    ClickEvent wrap(Event nativeEvent) {
      return new ClickEvent(nativeEvent);
    }
  };

  /**
   * Constructor.
   * 
   * @param nativeEvent the native event object
   */
  public ClickEvent(Event nativeEvent) {
    super(nativeEvent);
  }

  @Override
  protected Type getType() {
    return TYPE;
  }

}
