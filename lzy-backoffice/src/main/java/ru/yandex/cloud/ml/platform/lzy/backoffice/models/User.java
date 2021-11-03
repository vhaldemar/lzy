package ru.yandex.cloud.ml.platform.lzy.backoffice.models;

import io.micronaut.core.annotation.Introspected;
import yandex.cloud.priv.datasphere.v2.lzy.BackOffice;

@Introspected
public class User {
    private String userId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BackOffice.User toModel(){
        return BackOffice.User.newBuilder()
                .setUserId(userId)
                .build();
    }

    public User(String userId) {
        this.userId = userId;
    }

    public static User fromModel(BackOffice.User user){
        return new User(user.getUserId());
    }
}
