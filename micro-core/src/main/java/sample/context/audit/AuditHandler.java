package sample.context.audit;

import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.*;

import lombok.Setter;
import sample.*;
import sample.context.actor.*;
import sample.context.audit.AuditActor.RegAuditActor;
import sample.context.audit.AuditEvent.RegAuditEvent;
import sample.context.orm.SystemRepository;

/**
 * 利用者監査やシステム監査(定時バッチや日次バッチ等)などを取り扱います。
 * <p>暗黙的な適用を望む場合は、AOPとの連携も検討してください。 
 * <p>対象となるログはLoggerだけでなく、システムスキーマの監査テーブルへ書きだされます。
 * (開始時と完了時で別TXにする事で応答無し状態を検知可能)
 */
@Setter
public class AuditHandler {
    public static final Logger LoggerActor = LoggerFactory.getLogger("Audit.Actor");
    public static final Logger LoggerEvent = LoggerFactory.getLogger("Audit.Event");
    protected Logger loggerSystem = LoggerFactory.getLogger(getClass());

    @Autowired
    private ActorSession session;
    @Autowired
    private AuditPersister persister;

    /** 与えた処理に対し、監査ログを記録します。 */
    public <T> T audit(String message, final Supplier<T> callable) {
        return audit("default", message, callable);
    }

    /** 与えた処理に対し、監査ログを記録します。 */
    public void audit(String message, final Runnable command) {
        audit(message, () -> {
            command.run();
            return true;
        });
    }

    /** 与えた処理に対し、監査ログを記録します。 */
    public <T> T audit(String category, String message, final Supplier<T> callable) {
        logger().trace(message(message, "[開始]", null));
        long start = System.currentTimeMillis();
        try {
            T v = session.actor().getRoleType().isSystem() ? callEvent(category, message, callable)
                    : callAudit(category, message, callable);
            logger().info(message(message, "[完了]", start));
            return v;
        } catch (ValidationException e) {
            logger().warn(message(message, "[審例]", start));
            throw e;
        } catch (RuntimeException e) {
            logger().error(message(message, "[例外]", start));
            throw (RuntimeException) e;
        } catch (Exception e) {
            logger().error(message(message, "[例外]", start));
            throw new InvocationException("error.Exception", e);
        }
    }

    /** 与えた処理に対し、監査ログを記録します。 */
    public void audit(String category, String message, final Runnable command) {
        audit(category, message, () -> {
            command.run();
            return true;
        });
    }

    private Logger logger() {
        return session.actor().getRoleType().isSystem() ? LoggerEvent : LoggerActor;
    }

    private String message(String message, String prefix, Long startMillis) {
        Actor actor = session.actor();
        StringBuilder sb = new StringBuilder(prefix + " ");
        if (actor.getRoleType().notSystem()) {
            sb.append("[" + actor.getId() + "] ");
        }
        sb.append(message);
        if (startMillis != null) {
            sb.append(" [" + (System.currentTimeMillis() - startMillis) + "ms]");
        }
        return sb.toString();
    }

    public <T> T callAudit(String category, String message, final Supplier<T> callable) {
        Optional<AuditActor> audit = Optional.empty();
        try {
            try { // システムスキーマの障害は本質的なエラーに影響を与えないように
                audit = Optional.of(persister.start(RegAuditActor.of(category, message)));
            } catch (Exception e) {
                loggerSystem.error(e.getMessage(), e);
            }
            T v = callable.get();
            try {
                audit.ifPresent(persister::finish);
            } catch (Exception e) {
                loggerSystem.error(e.getMessage(), e);
            }
            return v;
        } catch (ValidationException e) {
            try {
                audit.ifPresent((v) -> persister.cancel(v, e.getMessage()));
            } catch (Exception ex) {
                loggerSystem.error(ex.getMessage(), ex);
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                audit.ifPresent((v) -> persister.error(v, e.getMessage()));
            } catch (Exception ex) {
                loggerSystem.error(ex.getMessage(), ex);
            }
            throw e;
        } catch (Exception e) {
            try {
                audit.ifPresent((v) -> persister.error(v, e.getMessage()));
            } catch (Exception ex) {
                loggerSystem.error(ex.getMessage(), ex);
            }
            throw new InvocationException(e);
        }
    }

    public <T> T callEvent(String category, String message, final Supplier<T> callable) {
        Optional<AuditEvent> audit = Optional.empty();
        try {
            try { // システムスキーマの障害は本質的なエラーに影響を与えないように
                audit = Optional.of(persister.start(RegAuditEvent.of(category, message)));
            } catch (Exception e) {
                loggerSystem.error(e.getMessage(), e);
            }
            T v = callable.get();
            try {
                audit.ifPresent(persister::finish);
            } catch (Exception e) {
                loggerSystem.error(e.getMessage(), e);
            }
            return v;
        } catch (ValidationException e) {
            try {
                audit.ifPresent((v) -> persister.cancel(v, e.getMessage()));
            } catch (Exception ex) {
                loggerSystem.error(ex.getMessage(), ex);
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                audit.ifPresent((v) -> persister.error(v, e.getMessage()));
            } catch (Exception ex) {
                loggerSystem.error(ex.getMessage(), ex);
            }
            throw (RuntimeException) e;
        } catch (Exception e) {
            try {
                audit.ifPresent((v) -> persister.error(v, e.getMessage()));
            } catch (Exception ex) {
                loggerSystem.error(ex.getMessage(), ex);
            }
            throw new InvocationException(e);
        }
    }

    /**
     * 監査ログをシステムスキーマへ永続化します。
     */
    public static class AuditPersister {
        @Autowired
        private SystemRepository rep;

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditActor start(RegAuditActor p) {
            return AuditActor.register(rep, p);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditActor finish(AuditActor audit) {
            return audit.finish(rep);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditActor cancel(AuditActor audit, String errorReason) {
            return audit.cancel(rep, errorReason);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditActor error(AuditActor audit, String errorReason) {
            return audit.error(rep, errorReason);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditEvent start(RegAuditEvent p) {
            return AuditEvent.register(rep, p);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditEvent finish(AuditEvent event) {
            return event.finish(rep);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditEvent cancel(AuditEvent event, String errorReason) {
            return event.cancel(rep, errorReason);
        }

        @Transactional(value = SystemRepository.BeanNameTx, propagation = Propagation.REQUIRES_NEW)
        public AuditEvent error(AuditEvent event, String errorReason) {
            return event.error(rep, errorReason);
        }
    }

}
