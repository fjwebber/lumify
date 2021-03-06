package io.lumify.web;

import io.lumify.core.bootstrap.InjectHelper;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workQueue.WorkQueueRepository;
import io.lumify.core.user.User;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.web.clientapi.model.UserStatus;

import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import java.io.Serializable;

public class SessionUser implements HttpSessionBindingListener, Serializable {
    private static final long serialVersionUID = -4886360466524045992L;
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(SessionUser.class);
    private String userId;

    public SessionUser(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    @Override
    public void valueBound(HttpSessionBindingEvent event) {
        // do nothing
    }

    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        UserStatus status = UserStatus.OFFLINE;
        LOGGER.info("setting userId %s status to %s", userId, status);
        try {
            UserRepository userRepository = InjectHelper.getInstance(UserRepository.class);
            User user = userRepository.setStatus(userId, status);
            WorkQueueRepository workQueueRepository = InjectHelper.getInstance(WorkQueueRepository.class);
            workQueueRepository.pushUserStatusChange(user, status);
        } catch (Exception ex) {
            LOGGER.error("exception while setting userId %s status to %s", userId, status, ex);
        }
    }
}
