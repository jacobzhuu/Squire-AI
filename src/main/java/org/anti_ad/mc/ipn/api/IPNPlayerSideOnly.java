package org.anti_ad.mc.ipn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 告诉 IPN：这个界面的「容器那一侧」不是箱子，别碰，只整理玩家自己的背包。
 *
 * <p>签名照抄 IPN 公开 API，见 {@link IPNButton}。IPN 内部的做法是把容器类型里的
 * {@code SORTABLE_STORAGE} 换成 {@code PURE_BACKPACK}——排序只在玩家那 36 格里发生，
 * 「全部搬过去/搬回来」两个按钮也随之消失。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IPNPlayerSideOnly {
}
