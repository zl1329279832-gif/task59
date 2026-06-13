package com.gk.study.controller;

import com.gk.study.common.APIResponse;
import com.gk.study.common.ResponeCode;
import com.gk.study.entity.Order;
import com.gk.study.entity.Recommend;
import com.gk.study.entity.RecEntity;
import com.gk.study.entity.Record;
import com.gk.study.entity.Thing;
import com.gk.study.entity.User;
import com.gk.study.entity.UserCF;
import com.gk.study.permission.Access;
import com.gk.study.permission.AccessLevel;
import com.gk.study.service.OrderService;
import com.gk.study.service.RecordService;
import com.gk.study.service.ThingService;
import com.gk.study.service.UserService;
import com.gk.study.utils.IpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/thing")
public class ThingController {

    private final static Logger logger = LoggerFactory.getLogger(ThingController.class);

    @Autowired
    ThingService service;

    @Autowired
    RecordService recordService;

    @Autowired
    OrderService orderService;

    @Autowired
    UserService userService;

    @Value("${File.uploadPath}")
    private String uploadPath;

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public APIResponse list(String keyword, String sort, String c, String cc){
        List<Thing> list =  service.getThingList(keyword, sort, c, cc);

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", list);
    }

    @RequestMapping(value = "/detail", method = RequestMethod.GET)
    public APIResponse detail(HttpServletRequest request, String id){
        Thing thing =  service.getThingById(id);


        // ------------------保存浏览记录--------------------
        String ip = IpUtils.getIpAddr(request);
        Record record = recordService.getRecord(thing.getId(), ip);
        if(record != null){
            record.setScore(record.getScore() + 1);
            recordService.updateRecord(record);
        }else {
            Record entity = new Record();
            entity.setThingId(thing.getId());
            entity.setIp(ip);
            entity.setScore(1);
            recordService.createRecord(entity);
        }

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", thing);
    }

    // 推荐接口
    @RequestMapping(value = "/recommend", method = RequestMethod.GET)
    public APIResponse recommend(HttpServletRequest request){

        // 获取ip列表
        List<String> ips = recordService.getRecordIpList();
        if (ips == null) {
            ips = new ArrayList<>();
        }

        List<UserCF> users = new ArrayList<>();
        for(String ip : ips){
            // 获取ip对于的物品
            List<Record> recordList = recordService.getRecordListByIp(ip);
            if (recordList == null || recordList.isEmpty()) {
                continue;
            }
            UserCF userCF = new UserCF(ip);
            for(Record record: recordList){
                userCF.set(record.thingId, record.score);
            }
            users.add(userCF);
        }

        List<Thing> thingList;

        if(users.size() <= 1){
            // 1个用户不满足协同推荐条件
            thingList = service.getDefaultThingList();
        }else {
            Recommend recommend = new Recommend();
            String currentIp = IpUtils.getIpAddr(request);
            List<RecEntity> recommendList = recommend.recommend(currentIp, users);
            // 去重thingId
            Set<Long> seen = new LinkedHashSet<>();
            List<Long> thingIdList = new ArrayList<>();
            for (RecEntity rec : recommendList) {
                if (seen.add(rec.thingId)) {
                    thingIdList.add(rec.thingId);
                }
            }
            if(thingIdList.size() > 0){
                thingList = service.getThingListByThingIds(thingIdList);
                if(thingList == null || thingList.isEmpty()){
                    // 如推荐量太少，则走默认
                    thingList = service.getDefaultThingList();
                }
            }else {
                thingList = service.getDefaultThingList();
            }
        }

        // 已购过滤：如果用户已登录，过滤掉已购买的资源
        String token = request.getHeader("TOKEN");
        if (!StringUtils.isEmpty(token)) {
            try {
                User user = userService.getUserByToken(token);
                if (user != null) {
                    List<Order> orders = orderService.getUserOrderList(user.getId(), null);
                    if (orders != null && !orders.isEmpty()) {
                        Set<String> purchasedIds = orders.stream()
                                .map(Order::getThingId)
                                .collect(Collectors.toSet());
                        thingList = thingList.stream()
                                .filter(t -> !purchasedIds.contains(String.valueOf(t.getId())))
                                .collect(Collectors.toList());
                    }
                }
            } catch (Exception e) {
                logger.warn("过滤已购资源时异常", e);
            }
        }

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", thingList);
    }

    @Access(level = AccessLevel.ADMIN)
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    @Transactional
    public APIResponse create(Thing thing) throws IOException {
        String url = saveThing(thing);
        if(!StringUtils.isEmpty(url)) {
            thing.cover = url;
        }

        service.createThing(thing);
        return new APIResponse(ResponeCode.SUCCESS, "创建成功");
    }

    @Access(level = AccessLevel.ADMIN)
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public APIResponse delete(String ids){
        System.out.println("ids===" + ids);
        // 批量删除
        String[] arr = ids.split(",");
        for (String id : arr) {
            service.deleteThing(id);
        }
        return new APIResponse(ResponeCode.SUCCESS, "删除成功");
    }

    @Access(level = AccessLevel.ADMIN)
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    @Transactional
    public APIResponse update(Thing thing) throws IOException {
        System.out.println(thing);
        String url = saveThing(thing);
        if(!StringUtils.isEmpty(url)) {
            thing.cover = url;
        }

        service.updateThing(thing);
        return new APIResponse(ResponeCode.SUCCESS, "更新成功");
    }

    // 评分
    @RequestMapping(value = "/rate", method = RequestMethod.POST)
    @Transactional
    public APIResponse rate(String thingId, int rate) throws IOException {
        Thing thing = service.getThingById(thingId);
        thing.rate = String.valueOf((Integer.parseInt(thing.rate) + rate)/2);
        service.updateThing(thing);
        return new APIResponse(ResponeCode.SUCCESS, "成功");
    }

    public String saveThing(Thing thing) throws IOException {
        MultipartFile file = thing.getImageFile();
        String newFileName = null;
        if(file !=null && !file.isEmpty()) {

            // 存文件
            String oldFileName = file.getOriginalFilename();
            String randomStr = UUID.randomUUID().toString();
            newFileName = randomStr + oldFileName.substring(oldFileName.lastIndexOf("."));
            String filePath = uploadPath + File.separator + "image" + File.separator + newFileName;
            File destFile = new File(filePath);
            if(!destFile.getParentFile().exists()){
                destFile.getParentFile().mkdirs();
            }
            file.transferTo(destFile);
        }
        if(!StringUtils.isEmpty(newFileName)) {
            thing.cover = newFileName;
        }
        return newFileName;
    }

    @RequestMapping(value = "/listUserThing", method = RequestMethod.GET)
    public APIResponse listUserThing(String userId){
        List<Thing> list =  service.getUserThing(userId);

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", list);
    }
}
