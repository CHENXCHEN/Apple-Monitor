package top.misec.applemonitor.job;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import top.misec.applemonitor.config.AppCfg;
import top.misec.applemonitor.config.CfgSingleton;
import top.misec.applemonitor.config.CountryEnum;
import top.misec.applemonitor.config.PushConfig;
import top.misec.applemonitor.push.impl.BarkPush;
import top.misec.applemonitor.push.impl.FeiShuBotPush;
import top.misec.applemonitor.push.impl.WeComPush;
import top.misec.applemonitor.push.model.PushMetaInfo;
import top.misec.applemonitor.push.pojo.feishu.FeiShuPushDTO;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author MoshiCoCo
 */
@Slf4j
public class AppleMonitor {
    @Data
    static public class DeviceResult {
        private String storeName, deviceName, productStatus, deviceCode;
        private Boolean available;

        DeviceResult(String _storeName, String _deviceName, String _productStatus, String _deviceCode, Boolean _available) {
            storeName = _storeName;
            deviceName = _deviceName;
            productStatus = _productStatus;
            deviceCode = _deviceCode;
            available = _available;
        }
    }

    private final AppCfg CONFIG = CfgSingleton.getInstance().config;


    public void monitor() {

        List<List<String>> deviceCodeGroup = CONFIG.getAppleTaskConfig().getDeviceCodeList();

        //监视机型型号


        try {
            for (List<String> deviceCodes : deviceCodeGroup) {
                doMonitor(deviceCodes);
                Thread.sleep(1500);
            }
        } catch (Exception e) {
            log.error("AppleMonitor Error", e);
        }
    }


    public static void pushAll(String content, List<PushConfig> pushConfigs) {
        log.info("push message: {}", content);
        pushConfigs.forEach(push -> {
            if (StrUtil.isAllNotEmpty(push.getBarkPushUrl(), push.getBarkPushToken())) {
                BarkPush.push(content, push.getBarkPushUrl(), push.getBarkPushToken());
            }
            if (StrUtil.isAllNotEmpty(push.getFeishuBotSecret(), push.getFeishuBotWebhooks())) {
                FeiShuBotPush.pushTextMessage(FeiShuPushDTO.builder()
                        .text(content).secret(push.getFeishuBotSecret())
                        .botWebHooks(push.getFeishuBotWebhooks())
                        .build());
            }
            if (StrUtil.isAllNotEmpty(push.getWE_COM_TOKEN())) {
                WeComPush.send(PushMetaInfo.builder().token(push.getWE_COM_TOKEN()).build(), content);
            }
        });
    }

    public void doMonitor(List<String> deviceCodes) {

        Map<String, Object> queryMap = new HashMap<>(5);
        queryMap.put("pl", "true");
        for (int i = 0; i < deviceCodes.size(); i++) {
            queryMap.put("mts." + i, "regular");
            queryMap.put("parts." + i, deviceCodes.get(i));
        }
        queryMap.put("location", CONFIG.getAppleTaskConfig().getLocation());

        String baseCountryUrl = CountryEnum.getUrlByCountry(CONFIG.getAppleTaskConfig().getCountry());

        Map<String, List<String>> headers = buildHeaders(baseCountryUrl, deviceCodes.get(0));

        String url = baseCountryUrl + "/shop/fulfillment-messages?" + URLUtil.buildQuery(queryMap, CharsetUtil.CHARSET_UTF_8);

        try {
            HttpResponse httpResponse = HttpRequest.get(url).header(headers).execute();
            if (!httpResponse.isOk()) {
                log.info("请求过于频繁，请调整cronExpressions，建议您参考推荐的cron表达式");
                return;
            }

            JSONObject responseJsonObject = JSONObject.parseObject(httpResponse.body());

            JSONObject pickupMessage = responseJsonObject.getJSONObject("body").getJSONObject("content").getJSONObject("pickupMessage");

            JSONArray stores = pickupMessage.getJSONArray("stores");

            if (stores == null) {
                log.info("您可能填错产品代码了，目前仅支持监控中国和日本地区的产品，注意不同国家的机型型号不同，下面是是错误信息");
                log.debug(pickupMessage.toString());
                return;
            }

            if (stores.isEmpty()) {
                log.info("您所在的 {} 附近没有Apple直营店，请检查您的地址是否正确", CONFIG.getAppleTaskConfig().getLocation());
            }

            Map<String, List<DeviceResult>> parseResult = stores.stream().filter(store -> {
                        if (CONFIG.getAppleTaskConfig().getStoreWhiteList().isEmpty()) {
                            return true;
                        } else {
                            return filterStore((JSONObject) store, CONFIG.getAppleTaskConfig().getStoreWhiteList());
                        }
                    }).map(k -> {
                        JSONObject storeJson = (JSONObject) k;

                        JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");

                        String storeName = storeJson.getString("storeName").trim();
                        ArrayList<DeviceResult> deviceResults = new ArrayList<>();
                        for (String deviceCode : partsAvailability.keySet()) {
                            JSONObject deviceData = partsAvailability.getJSONObject(deviceCode);
                            String deviceName = deviceData.getJSONObject("messageTypes").getJSONObject("regular").getString("storePickupProductTitle");
                            String productStatus = deviceData.getString("pickupSearchQuote");
                            boolean available = judgingStoreInventory(storeJson, deviceCode);
                            deviceResults.add(new DeviceResult(storeName, deviceName, productStatus, deviceCode, available));
                        }
                        return deviceResults;
                    })
                    .flatMap(Collection::stream)
                    .collect(Collectors.groupingBy(DeviceResult::getStoreName));

            for (Map.Entry<String, List<DeviceResult>> storeResult : parseResult.entrySet()) {
                String storeName = storeResult.getKey();
                List<DeviceResult> deviceResults = storeResult.getValue();
                ArrayList<DeviceResult> availableResults = deviceResults.stream()
                        .filter(DeviceResult::getAvailable)
                        .collect(Collectors.toCollection(ArrayList::new));
                if (availableResults.isEmpty()) {
                    log.info("门店:{},总计{}款型号,状态:{}", storeName
                            , deviceResults.size()
                            , deviceResults.get(0).productStatus);
                } else {
                    String context = String.format(
                            "门店:%s,型号:%s,状态:%s"
                            , storeName
                            , availableResults.stream().map(DeviceResult::getDeviceName).collect(Collectors.joining("/"))
                            , availableResults.get(0).productStatus
                    );
                    pushAll(context, CONFIG.getAppleTaskConfig().getPushConfigs());
                    log.info(context);
                }
            }

        } catch (Exception e) {
            log.error("AppleMonitor error", e);
        }

    }


    /**
     * check store inventory
     *
     * @param storeJson   store json
     * @param productCode product code
     * @return boolean
     */
    private boolean judgingStoreInventory(JSONObject storeJson, String productCode) {

        JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");
        String status = partsAvailability.getJSONObject(productCode).getString("pickupDisplay");
        return "available".equals(status);

    }

    /**
     * build pickup information
     *
     * @param retailStore retailStore
     * @return pickup message
     */
    private String buildPickupInformation(JSONObject retailStore) {
        String distanceWithUnit = retailStore.getString("distanceWithUnit");
        String twoLineAddress = retailStore.getJSONObject("address").getString("twoLineAddress");
        String daytimePhone = retailStore.getJSONObject("address").getString("daytimePhone");
        String lo = CONFIG.getAppleTaskConfig().getLocation();
        String messageTemplate = "\n取货地址:{},电话:{},距离{}:{}";
        return StrUtil.format(messageTemplate, twoLineAddress.replace("\n", " "), daytimePhone, lo, distanceWithUnit);
    }

    private boolean filterStore(JSONObject storeInfo, List<String> storeWhiteList) {
        String storeName = storeInfo.getString("storeName");
        return storeWhiteList.stream().anyMatch(k -> storeName.contains(k) || k.contains(storeName));
    }

    /**
     * build request headers
     *
     * @param baseCountryUrl base country url
     * @param productCode    product code
     * @return headers
     */
    private Map<String, List<String>> buildHeaders(String baseCountryUrl, String productCode) {

        ArrayList<String> referer = new ArrayList<>();
        referer.add(baseCountryUrl + "/shop/buy-iphone/iphone-14-pro/" + productCode);

        Map<String, List<String>> headers = new HashMap<>(10);
        headers.put(Header.REFERER.getValue(), referer);

        return headers;
    }
}
