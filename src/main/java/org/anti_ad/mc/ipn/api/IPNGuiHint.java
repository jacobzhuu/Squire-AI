package org.anti_ad.mc.ipn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 单个 IPN 按钮的位置修正。签名照抄 IPN 公开 API，见 {@link IPNButton}。
 *
 * <p>偏移量的语义（IPN 1.10 的 {@code SortingButtonCollectionWidget.reHint}）：按钮以
 * <b>界面背景矩形</b>的角为锚，{@code top}/{@code bottom} 是到上/下边的距离修正，
 * {@code horizontalOffset} 加在到右边的距离上——<b>越大越靠左</b>。</p>
 */
@Inherited
@Repeatable(IPNGuiHints.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IPNGuiHint {

	IPNButton button();

	int top() default 0;

	int bottom() default 0;

	int horizontalOffset() default 0;

	boolean hide() default false;
}
