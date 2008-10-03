/* Label.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Wed Jun  8 18:53:53     2005, Created by tomyeh
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package org.zkoss.zul;

import java.io.Writer;
import java.io.IOException;

import org.zkoss.lang.Objects;
import org.zkoss.xml.XMLs;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Components;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.sys.ContentRenderer;
import org.zkoss.zk.ui.sys.JsContentRenderer;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.metainfo.LanguageDefinition;

import org.zkoss.zul.impl.XulElement;

/**
 * A label.
 * 
 * @author tomyeh
 */
public class Label extends XulElement {
	private String _value = "";
	private int _maxlength;
	private boolean _multiline;

	public Label() {
	}
	public Label(String value) {
		setValue(value);
	}

	/** Returns the component type, zul.wgt.Label.
	 * @since 5.0.0
	 */
	public String getType() {
		return "zul.wgt.Label";
	}

	/** Returns the value.
	 * <p>Default: "".
	 */
	public String getValue() {
		return _value;
	}
	/** Sets the value.
	 */
	public void setValue(String value) {
		if (value == null)
			value = "";
		if (!Objects.equals(_value, value)) {
			_value = value;
			smartUpdate("value", getValue());
		}
	}

	/** Returns the maximal length of the label.
	 * <p>Default: 0 (means no limitation)
	 */
	public int getMaxlength() {
		return _maxlength;
	}
	/** Sets the maximal length of the label.
	 */
	public void setMaxlength(int maxlength) {
		if (maxlength < 0) maxlength = 0;
		if (_maxlength != maxlength) {
			_maxlength = maxlength;
			smartUpdate("maxlength", getMaxlength());
		}
	}
	/** Returns whether to preserve the new line and the white spaces at the
	 * begining of each line.
	 */
	public boolean isMultiline() {
		return _multiline;
	}
	/** Sets whether to preserve the new line and the white spaces at the
	 * begining of each line.
	 */
	public void setMultiline(boolean multiline) {
		if (_multiline != multiline) {
			_multiline = multiline;
			smartUpdate("multiline", isMultiline());
		}
	}
	/** @deprecated As of release 5.0.0, use CSS instead.
	 */
	public boolean isPre() {
		return false;
	}
	/** @deprecated As of release 5.0.0, use CSS instead.
	 *
	 * <p>Use the CSS style called "white-spacing: pre" to have the
	 * similar effect.
	 *
	 */
	public void setPre(boolean pre) {
	}
	/** @deprecated As of release 5.0.0, use CSS instead.
	 */
	public boolean isHyphen() {
		return false;
	}
	/** @deprecated As of release 5.0.0, use CSS instead.
	 *
	 * <p>Use the CSS style called "word-wrap: word-break"
	 * to have similar effect.
	 * Unfortunately, word-wrap is not applicable to
	 * FF and Opera(it works fine with IE and Safari).
	 */
	public void setHyphen(boolean hyphen) {
	}

	/** Whether to generate the value directly without ID.
	 * <p>Used only for component generated. Not for applications.
	 * @since 3.0.0
	 */
	public boolean isIdRequired() {
		final Component p = getParent();
		return p == null || !isVisible() 
			|| !isRawLabel(p) || !Components.isAutoId(getId())
			|| isAsapRequired(Events.ON_CLICK)
			|| !isEmpty(getStyle()) || !isEmpty(getSclass())
			|| !isEmpty(getContext()) || !isEmpty(getTooltip())
			|| !isEmpty(getTooltiptext()) || !isEmpty(getPopup())
			|| !"false".equals(getDraggable())
			|| !"false".equals(getDroppable())
			|| isAsapRequired(Events.ON_RIGHT_CLICK)
			|| !isEmpty(getAction())
			|| !isEmpty(getLeft()) || !isEmpty(getTop())
			|| !isEmpty(getWidth()) || !isEmpty(getHeight())
			|| isAsapRequired(Events.ON_DOUBLE_CLICK);
	}
	private static boolean isEmpty(String s) {
		return s == null || s.length() == 0;
	}
	private static boolean isRawLabel(Component comp) {
		final LanguageDefinition langdef =
			comp.getDefinition().getLanguageDefinition();
		return langdef != null && langdef.isRawLabel();
	}

	/** Returns the text for generating HTML content.
	 */
	private String getEncodedText() {
		StringBuffer sb = null;
		final int len = _value.length();
		if (_multiline) {
			int outcnt = Integer.MAX_VALUE/2; //avoid overflow (algorithm issue)
			if (_maxlength > 0 && _maxlength < outcnt) outcnt = _maxlength;

			for (int j = 0, k;; j = k + 1) {
				k = _value.indexOf('\n', j);
				if (k < 0) {
					final int v = j + outcnt;
					sb = XMLs.encodeText(sb, _value, j, len > v ? v: len);
					break; //done
				}

				if (sb == null) {
					assert j == 0;
					sb = new StringBuffer(len + 10);
				}

				final int l = k > j && _value.charAt(k - 1) == '\r' ? k - 1: k;
				final int v = l - j;
				if (v >= outcnt) {
					sb = XMLs.encodeText(sb, _value, j, j + outcnt);
					break; //done
				}

				outcnt -= v;
				sb = XMLs.encodeText(sb, _value, j, l);
				sb.append("<br/>");
			}
		} else {
			sb = XMLs.encodeText(sb, _value, 0,
				_maxlength > 0 && len > _maxlength ? _maxlength: len);
		}
		return sb != null ? sb.toString(): _value;
	}

	//-- super --//
	/** Returns the Style of label
	 *
	 * <p>Default: "z-label"
	 */
	public String getZclass() {
		String scls = super.getZclass();
		if (scls == null)
			scls = "z-label";
		return scls;
	}

	//-- super --//
	//super//
	protected void renderProperties(ContentRenderer renderer) {
		super.renderProperties(renderer);

		int maxlen = getMaxlength();
		if (maxlen > 0) renderer.render("maxlength", maxlen);
		render(renderer, "multiline", isMultiline());
	}
	public void redraw(Writer out) throws IOException {
		if (isIdRequired()) {
			final JsContentRenderer renderer = new JsContentRenderer();
			renderProperties(renderer);

			out.write("<div id=\"");
			out.write(getUuid());
			out.write("\"><span>");
			out.write(XMLs.encodeText(getValue()));
			out.write("</span><script>zkau.begin('");
			out.write(getType());
			out.write("','");
			out.write(getUuid());
			out.write("',{\n");
			out.write(renderer.getBuffer().toString());
			out.write("},'value');zkau.end();</script></div>\n");
		} else {
			out.write(getEncodedText());
			//no processing; direct output if not ZUL
		}
	}
	/** No child is allowed.
	 */
	protected boolean isChildable() {
		return false;
	}
}
