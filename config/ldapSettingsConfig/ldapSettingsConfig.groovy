/*
 * Copyright (C) 2015 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.artifactory.descriptor.security.ldap.LdapSetting
import org.artifactory.descriptor.security.ldap.SearchPattern
import org.artifactory.resource.ResourceStreamHandle
import org.artifactory.util.AlreadyExistsException

def propList = ['key': [
        CharSequence.class, 'string',
        { c, v -> c.key = v ?: null }
    ], 'enabled': [
        Boolean.class, 'boolean',
        { c, v -> c.enabled = v ?: false }
    ], 'ldapUrl': [
        CharSequence.class, 'string',
        { c, v -> c.ldapUrl = v ?: null }
    ], 'userDnPattern': [
        CharSequence.class, 'string',
        { c, v -> c.userDnPattern = v ?: null }
    ], 'searchFilter': [
        CharSequence.class, 'string',
        { c, v -> c.search.searchFilter = v ?: null }
    ], 'searchBase': [
        CharSequence.class, 'string',
        { c, v -> c.search.searchBase = v ?: null }
    ], 'searchSubTree': [
        Boolean.class, 'boolean',
        { c, v -> c.search.searchSubTree = v ?: false }
    ], 'managerDn': [
        CharSequence.class, 'string',
        { c, v -> c.search.managerDn = v ?: null }
    ], 'managerPassword': [
        CharSequence.class, 'string',
        { c, v -> c.search.managerPassword = v ?: null }
    ], 'autoCreateUser': [
        Boolean.class, 'boolean',
        { c, v -> c.autoCreateUser = v ?: false }
    ], 'emailAttribute': [
        CharSequence.class, 'string',
        { c, v -> c.emailAttribute = v ?: null }]]

executions {
    getLdapSettingsList(version: '1.0', httpMethod: 'GET') { params ->
        def cfg = ctx.centralConfig.descriptor.security.ldapSettings
        if (cfg == null) cfg = []
        def json = cfg.collect { it.key }
        message = new JsonBuilder(json).toPrettyString()
        status = 200
    }

    getLdapSetting(version: '1.0', httpMethod: 'GET') { params ->
        def key = params?.get('key')?.get(0) as String
        if (!key) {
            message = 'A setting key is required'
            status = 400
            return
        }
        def setting = ctx.centralConfig.descriptor.security.getLdapSettings(key)
        if (setting == null) {
            message = "Setting with key '$key' does not exist"
            status = 404
            return
        }
        def json = [
            key: setting.key ?: null,
            enabled: setting.isEnabled() ?: false,
            ldapUrl: setting.ldapUrl ?: null,
            userDnPattern: setting.userDnPattern ?: null,
            searchFilter: setting.search.searchFilter ?: null,
            searchBase: setting.search.searchBase ?: null,
            searchSubTree: setting.search.isSearchSubTree() ?: false,
            managerDn: setting.search.managerDn ?: null,
            managerPassword: setting.search.managerPassword ?: null,
            autoCreateUser: setting.isAutoCreateUser() ?: false,
            emailAttribute: setting.emailAttribute ?: null]
        message = new JsonBuilder(json).toPrettyString()
        status = 200
    }

    deleteLdapSetting(version: '1.0', httpMethod: 'DELETE') { params ->
        def key = params?.get('key')?.get(0) as String
        if (!key) {
            message = 'A setting key is required'
            status = 400
            return
        }
        def cfg = ctx.centralConfig.mutableDescriptor
        def setting = cfg.security.removeLdap(key)
        if (setting == null) {
            message = "Setting with key '$key' does not exist"
            status = 404
            return
        }
        ctx.centralConfig.descriptor = cfg
        status = 200
    }

    addLdapSetting(version: '1.0') { params, ResourceStreamHandle body ->
        def reader = new InputStreamReader(body.inputStream, 'UTF-8')
        def json = null
        try {
            json = new JsonSlurper().parse(reader)
        } catch (groovy.json.JsonException ex) {
            message = "Problem parsing JSON: $ex.message"
            status = 400
            return
        }
        if (!(json instanceof Map)) {
            message = 'Provided value must be a JSON object'
            status = 400
            return
        }
        if (!json['key']) {
            message = 'A setting key is required'
            status = 400
            return
        }
        if (!(json['key'] ==~ '[_:a-zA-Z][-._:a-zA-Z0-9]*')) {
            message = 'A setting key may not contain special characters'
            status = 400
            return
        }
        if (!json['ldapUrl']) {
            message = 'An LDAP URL is required'
            status = 400
            return
        }
        def rexlld = '[a-zA-Z0-9]([-a-zA-Z0-9]*[a-zA-Z0-9])?'
        def rextld = '[a-zA-Z]([-a-zA-Z0-9]*[a-zA-Z0-9])?'
        def rexdom = "($rexlld\\.)*$rextld"
        def rexip = '[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+'
        def rexurl = "ldaps?://($rexdom|$rexip)(:[0-9]+)?.*"
        if (!(json['ldapUrl'] ==~ rexurl)) {
            message = 'The LDAP URL must be a valid LDAP URL'
            status = 400
            return
        }
        def err = null
        def setting = new LdapSetting()
        setting.search = new SearchPattern()
        propList.each { k, v ->
            if (!err && json[k] != null && !(v[0].isInstance(json[k]))) {
                err = "Property '$k' is type"
                err += " '${json[k].getClass().name}',"
                err += " should be a ${v[1]}"
            } else v[2](setting, json[k])
        }
        if (err) {
            message = err
            status = 400
            return
        }
        def cfg = ctx.centralConfig.mutableDescriptor
        try {
            cfg.security.addLdap(setting)
        } catch (AlreadyExistsException ex) {
            message = "Setting with key '${json['key']}' already exists"
            status = 409
            return
        }
        ctx.centralConfig.descriptor = cfg
        status = 200
    }

    updateLdapSetting(version: '1.0') { params, ResourceStreamHandle body ->
        def key = params?.get('key')?.get(0) as String
        if (!key) {
            message = 'A setting key is required'
            status = 400
            return
        }
        def cfg = ctx.centralConfig.mutableDescriptor
        def setting = cfg.security.getLdapSettings(key)
        if (setting == null) {
            message = "Setting with key '$key' does not exist"
            status = 404
            return
        }
        def reader = new InputStreamReader(body.inputStream, 'UTF-8')
        def json = null
        try {
            json = new JsonSlurper().parse(reader)
        } catch (groovy.json.JsonException ex) {
            message = "Problem parsing JSON: $ex.message"
            status = 400
            return
        }
        if (!(json instanceof Map)) {
            message = 'Provided JSON value must be a JSON object'
            status = 400
            return
        }
        if ('key' in json.keySet()) {
            if (!json['key']) {
                message = 'A setting key must not be empty'
                status = 400
                return
            } else if (!(json['key'] ==~ '[_:a-zA-Z][-._:a-zA-Z0-9]*')) {
                message = 'A setting key may not contain special characters'
                status = 400
                return
            } else if (json['key'] != key
                       && cfg.security.isLdapExists(json['key'])) {
                message = "Setting with key '${json['key']}' already exists"
                status = 409
                return
            }
        }
        if ('ldapUrl' in json.keySet()) {
            if (!json['ldapUrl']) {
                message = 'An LDAP URL must not be empty'
                status = 400
                return
            }
            def rexlld = '[a-zA-Z0-9]([-a-zA-Z0-9]*[a-zA-Z0-9])?'
            def rextld = '[a-zA-Z]([-a-zA-Z0-9]*[a-zA-Z0-9])?'
            def rexdom = "($rexlld\\.)*$rextld"
            def rexip = '[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+'
            def rexurl = "ldaps?://($rexdom|$rexip)(:[0-9]+)?.*"
            if (!(json['ldapUrl'] ==~ rexurl)) {
                message = 'The LDAP URL must be a valid LDAP URL'
                status = 400
                return
            }
        }
        def err = null
        propList.each { k, v ->
            if (!err && k in json.keySet()) {
                if (json[k] && !(v[0].isInstance(json[k]))) {
                    err = "Property '$k' is type"
                    err += " '${json[k].getClass().name}',"
                    err += " should be a ${v[1]}"
                } else v[2](setting, json[k])
            }
        }
        if (err) {
            message = err
            status = 400
            return
        }
        cfg.security.ldapSettingChanged(setting)
        ctx.centralConfig.descriptor = cfg
        status = 200
    }
}
