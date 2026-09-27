package com.chris64233.numberporting.support;

import java.lang.reflect.Field;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.numberporting.domain.PortEvent;

/** 测试专用：尝试以越权方式篡改/删除已落库事件，验证不可修改保护。 */
@Component
public class EventTamper {

    @PersistenceContext
    private EntityManager em;

    /** 反射修改已持久化事件字段并 flush，应被 @PreUpdate 拒绝。 */
    @Transactional
    public void tryUpdate(Long eventId) {
        PortEvent event = em.find(PortEvent.class, eventId);
        try {
            Field detail = PortEvent.class.getDeclaredField("detail");
            detail.setAccessible(true);
            detail.set(event, "被篡改的内容");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        em.flush();
    }

    /** 删除已持久化事件，应被 @PreRemove 拒绝。 */
    @Transactional
    public void tryDelete(Long eventId) {
        PortEvent event = em.find(PortEvent.class, eventId);
        em.remove(event);
        em.flush();
    }
}
