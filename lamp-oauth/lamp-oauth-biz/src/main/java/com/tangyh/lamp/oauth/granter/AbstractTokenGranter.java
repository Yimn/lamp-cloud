/*
 * Copyright 2006-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.tangyh.lamp.oauth.granter;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.servlet.ServletUtil;
import com.tangyh.basic.base.R;
import com.tangyh.basic.boot.utils.WebUtils;
import com.tangyh.basic.context.ContextUtil;
import com.tangyh.basic.database.properties.DatabaseProperties;
import com.tangyh.basic.database.properties.MultiTenantType;
import com.tangyh.basic.exception.code.ExceptionCode;
import com.tangyh.basic.jwt.TokenUtil;
import com.tangyh.basic.jwt.model.AuthInfo;
import com.tangyh.basic.jwt.model.JwtUserInfo;
import com.tangyh.basic.jwt.utils.JwtUtil;
import com.tangyh.basic.utils.BeanPlusUtil;
import com.tangyh.basic.utils.BizAssert;
import com.tangyh.basic.utils.SpringUtils;
import com.tangyh.basic.utils.StrHelper;
import com.tangyh.basic.utils.StrPool;
import com.tangyh.lamp.authority.dto.auth.LoginParamDTO;
import com.tangyh.lamp.authority.dto.auth.Online;
import com.tangyh.lamp.authority.entity.auth.Application;
import com.tangyh.lamp.authority.entity.auth.User;
import com.tangyh.lamp.authority.service.auth.ApplicationService;
import com.tangyh.lamp.authority.service.auth.OnlineService;
import com.tangyh.lamp.authority.service.auth.UserService;
import com.tangyh.lamp.oauth.event.LoginEvent;
import com.tangyh.lamp.oauth.event.model.LoginStatusDTO;
import com.tangyh.lamp.oauth.utils.TimeUtils;
import com.tangyh.lamp.tenant.entity.Tenant;
import com.tangyh.lamp.tenant.enumeration.TenantStatusEnum;
import com.tangyh.lamp.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

import static com.tangyh.basic.context.ContextConstants.BASIC_HEADER_KEY;
import static com.tangyh.basic.utils.BizAssert.gt;
import static com.tangyh.basic.utils.BizAssert.notNull;

/**
 * ?????????TokenGranter
 *
 * @author zuihou
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTokenGranter implements TokenGranter {
    protected final TokenUtil tokenUtil;
    protected final UserService userService;
    protected final TenantService tenantService;
    protected final ApplicationService applicationService;
    protected final DatabaseProperties databaseProperties;
    protected final OnlineService onlineService;

    /**
     * ??????????????????
     *
     * @param loginParam ????????????
     * @return ????????????
     */
    protected R<AuthInfo> login(LoginParamDTO loginParam) {
        if (StrHelper.isAnyBlank(loginParam.getAccount(), loginParam.getPassword())) {
            return R.fail("???????????????????????????");
        }
        // 1???????????????????????????
        if (!MultiTenantType.NONE.eq(databaseProperties.getMultiTenantType())) {
            Tenant tenant = this.tenantService.getByCode(ContextUtil.getTenant());
            notNull(tenant, "???????????????");
            BizAssert.equals(TenantStatusEnum.NORMAL, tenant.getStatus(), "???????????????~");
            if (tenant.getExpirationTime() != null) {
                gt(LocalDateTime.now(), tenant.getExpirationTime(), "?????????????????????~");
            }
        }

        // 2.??????client????????????
        R<String[]> checkClient = checkClient();
        if (!checkClient.getIsSuccess()) {
            return R.fail(checkClient.getMsg());
        }

        // 3. ????????????
        R<User> result = this.getUser(loginParam.getAccount(), loginParam.getPassword());
        if (!result.getIsSuccess()) {
            return R.fail(result.getCode(), result.getMsg());
        }

        // 4.?????? token
        User user = result.getData();
        AuthInfo authInfo = this.createToken(user);

        Online online = getOnline(checkClient.getData()[0], authInfo);

        //??????????????????
        LoginStatusDTO loginStatus = LoginStatusDTO.success(user.getId(), online);
        SpringUtils.publishEvent(new LoginEvent(loginStatus));

        onlineService.save(online);
        return R.success(authInfo);
    }

    protected Online getOnline(String clientId, AuthInfo authInfo) {
        Online online = new Online();
        BeanPlusUtil.copyProperties(authInfo, online);
        online.setClientId(clientId);
        online.setExpireTime(authInfo.getExpiration());
        online.setLoginTime(LocalDateTime.now());
        return online;
    }


    /**
     * ?????? client
     */
    protected R<String[]> checkClient() {
        String basicHeader = ServletUtil.getHeader(WebUtils.request(), BASIC_HEADER_KEY, StrPool.UTF_8);
        String[] client = JwtUtil.getClient(basicHeader);
        Application application = applicationService.getByClient(client[0], client[1]);

        if (application == null) {
            return R.fail("???????????????????????????ID?????????????????????");
        }
        if (!application.getState()) {
            return R.fail("?????????[%s]????????????", application.getClientId());
        }
        return R.success(client);
    }


    /**
     * ??????????????????????????????
     *
     * @param account  ??????
     * @param password ??????
     * @return ????????????
     */
    protected R<User> getUser(String account, String password) {
        User user = this.userService.getByAccount(account);
        // ????????????
        if (user == null) {
            return R.fail(ExceptionCode.JWT_USER_INVALID);
        }

        String passwordMd5 = SecureUtil.sha256(password + user.getSalt());
        if (!passwordMd5.equalsIgnoreCase(user.getPassword())) {
            String msg = "????????????????????????!";
            // ??????????????????
            SpringUtils.publishEvent(new LoginEvent(LoginStatusDTO.pwdError(user.getId(), msg)));
            return R.fail(msg);
        }

        // ????????????
        if (user.getPasswordExpireTime() != null && LocalDateTime.now().isAfter(user.getPasswordExpireTime())) {
            String msg = "??????????????????????????????????????????????????????????????????!";
            SpringUtils.publishEvent(new LoginEvent(LoginStatusDTO.fail(user.getId(), msg)));
            return R.fail(msg);
        }

        if (!user.getState()) {
            String msg = "???????????????????????????????????????";
            SpringUtils.publishEvent(new LoginEvent(LoginStatusDTO.fail(user.getId(), msg)));
            return R.fail(msg);
        }

        //TODO ????????????
        Integer maxPasswordErrorNum = 0;
        Integer passwordErrorNum = Convert.toInt(user.getPasswordErrorNum(), 0);
        if (maxPasswordErrorNum > 0 && passwordErrorNum > maxPasswordErrorNum) {
            log.info("??????????????????{}, ????????????:{}", passwordErrorNum, maxPasswordErrorNum);

            LocalDateTime passwordErrorLockTime = TimeUtils.getPasswordErrorLockTime("0");
            log.info("passwordErrorLockTime={}", passwordErrorLockTime);
            if (passwordErrorLockTime.isAfter(user.getPasswordErrorLastTime())) {
                // ??????????????????
                String msg = StrUtil.format("?????????????????????????????????{}???,??????????????????~", maxPasswordErrorNum);
                SpringUtils.publishEvent(new LoginEvent(LoginStatusDTO.fail(user.getId(), msg)));
                return R.fail(msg);
            }
        }

        return R.success(user);
    }

    /**
     * ????????????TOKEN
     *
     * @param user ??????
     * @return token
     */
    protected AuthInfo createToken(User user) {
        JwtUserInfo userInfo = new JwtUserInfo(user.getId(), user.getAccount(), user.getName());
        AuthInfo authInfo = tokenUtil.createAuthInfo(userInfo, null);
        authInfo.setAvatar(user.getAvatar());
        authInfo.setWorkDescribe(user.getWorkDescribe());
        return authInfo;
    }


}
