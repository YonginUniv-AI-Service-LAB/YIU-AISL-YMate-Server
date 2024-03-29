package yiu.aisl.yiuservice.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.security.SecurityUtil;
import org.aspectj.weaver.ast.Not;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.MethodArgumentNotValidException;
import yiu.aisl.yiuservice.domain.*;
import yiu.aisl.yiuservice.domain.state.ApplyState;
import yiu.aisl.yiuservice.domain.state.EntityCode;
import yiu.aisl.yiuservice.domain.state.PostState;
import yiu.aisl.yiuservice.dto.DeliveryRequest;
import yiu.aisl.yiuservice.dto.DeliveryResponse;
import yiu.aisl.yiuservice.exception.CustomException;
import yiu.aisl.yiuservice.exception.ErrorCode;
import yiu.aisl.yiuservice.repository.Comment_DeliveryRepository;
import yiu.aisl.yiuservice.repository.DeliveryRepository;
import yiu.aisl.yiuservice.repository.PushRepository;
import yiu.aisl.yiuservice.repository.UserRepository;
import yiu.aisl.yiuservice.security.TokenProvider;

import java.rmi.UnexpectedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class DeliveryService {

    private final FirebaseMessaging firebaseMessaging;
    private final DeliveryRepository deliveryRepository;
    private final Comment_DeliveryRepository comment_deliveryRepository;
    private final UserRepository userRepository;
    private final PushRepository pushRepository;

    // 전체 배달모집글 조회 [all]
    @Transactional
    public List<DeliveryResponse> getList() throws Exception {
        try {
            List<Delivery> listActive = deliveryRepository.findByStateOrderByCreatedAtDesc(PostState.ACTIVE);
            List<Delivery> listDeleted = deliveryRepository.findByStateOrderByCreatedAtDesc(PostState.DELETED);
            List<Delivery> listFinished = deliveryRepository.findByStateOrderByCreatedAtDesc(PostState.FINISHED);

            // 현재 시간과 비교하여 due가 이미 지났다면 상태를 모두 FINISHED로 변경
            LocalDateTime currentTime = LocalDateTime.now();
            listFinished.addAll(listActive.stream()
                    .filter(delivery -> delivery.getDue().isBefore(currentTime))
                    .map(delivery -> {
                        delivery.setState(PostState.FINISHED);

                        waitToFinish(delivery); // 대기 신청글 => 마감처리

                        return delivery;
                    })
                    .collect(Collectors.toList()));

            List<DeliveryResponse> getListDTO = new ArrayList<>();
            getListDTO.addAll(listActive.stream().map(DeliveryResponse::GetDeliveryDTO).collect(Collectors.toList()));
            getListDTO.addAll(listDeleted.stream().map(DeliveryResponse::GetDeliveryDTO).collect(Collectors.toList()));
            getListDTO.addAll(listFinished.stream().map(DeliveryResponse::GetDeliveryDTO).collect(Collectors.toList()));

            return getListDTO;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // 배달모집글 상세조회 [all]
    public DeliveryResponse getDetail(DeliveryRequest.DetailDTO request) throws Exception {
        // 400 - 데이터 없음
        if(request.getDId().describeConstable().isEmpty()) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 404 - 글 존재하지 않음
        Delivery delivery = deliveryRepository.findBydId(request.getDId()).orElseThrow(() -> {
            throw new CustomException(ErrorCode.NOT_EXIST);
        });

        // 현재 시간과 비교하여 due가 이미 지났다면 상태를 FINISHED로 변경
        LocalDateTime currentTime = LocalDateTime.now();
        if (delivery.getDue().isBefore(currentTime)) {
            delivery.setState(PostState.FINISHED);
            waitToFinish(delivery); // 대기 신청글 => 마감처리
        }

        // 409 - 삭제된 글
        if(delivery.getState().equals(PostState.DELETED)) throw new CustomException(ErrorCode.CONFLICT);

        try {
            DeliveryResponse response = DeliveryResponse.GetDeliveryDetailDTO(delivery);
            return response;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

    }


    // 배달모집글 작성 [writer]
    @Transactional
    public Boolean create(Long studentId, DeliveryRequest.CreateDTO request) throws Exception{
        // 400 - 데이터 없음
        if(request.getTitle() == null || request.getContents() == null || request.getDue() == null)
            throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 400 - 음식, 위치
        if((request.getFood() == null || request.getFood().isEmpty()) && request.getFoodCode() == null)
            throw new CustomException(ErrorCode.INSUFFICIENT_DATA);
        // 400 - 위치
        if((request.getLocation() == null || request.getLocation().isEmpty()) && request.getLocationCode() == null)
            throw new CustomException(ErrorCode.INSUFFICIENT_DATA);



        try {
            // 유저 확인 404 포함
            User user = findByStudentId(studentId);

            Delivery delivery = Delivery.builder()
                    .user(user)
                    .title(request.getTitle())
                    .contents(request.getContents())
                    .due(request.getDue())
                    .food(request.getFood())
                    .foodCode(request.getFoodCode())
                    .location(request.getLocation())
                    .locationCode(request.getLocationCode())
                    .link(request.getLink())
                    .state(request.getState())
                    .build();
            deliveryRepository.save(delivery);
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return true;
    }

    // 배달모집글 수정 [writer]
    @Transactional
    public Boolean update(Long studentId, DeliveryRequest.UpdateDTO request) throws Exception{
        // 유저 확인 404 포함
        User user = findByStudentId(studentId);

        // 400 - 데이터 없음
        if(request.getDId() == null || request.getTitle() == null || request.getContents() == null || request.getDue() == null)
            throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 400 - 음식, 위치
        if((request.getFood() == null || request.getFood().isEmpty()) && request.getFoodCode() == null)
            throw new CustomException(ErrorCode.INSUFFICIENT_DATA);
        // 400 - 위치
        if((request.getLocation() == null || request.getLocation().isEmpty()) && request.getLocationCode() == null)
            throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        Optional<Delivery> optDelivery = deliveryRepository.findBydId(request.getDId());

        // 404 - 글이 존재하지 않음
        if(optDelivery.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_EXIST);
        }

        // 403 - 권한 없음
        Delivery existingDelivery = optDelivery.get();
        if(!existingDelivery.getUser().equals(user)) {
            throw new CustomException(ErrorCode.ACCESS_NO_AUTH);
        }

        try {
            existingDelivery.setTitle(request.getTitle());
            existingDelivery.setContents(request.getContents());
            existingDelivery.setDue(request.getDue());
            existingDelivery.setFood(request.getFood());
            existingDelivery.setFoodCode(request.getFoodCode());
            existingDelivery.setLocation(request.getLocation());
            existingDelivery.setLocationCode(request.getLocationCode());
            existingDelivery.setLink(request.getLink());
            existingDelivery.setState(request.getPostState());
            deliveryRepository.save(existingDelivery);
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return true;
    }

    // 배달모집글 삭제 [writer]
    @Transactional
    public Boolean delete(Long studentId, DeliveryRequest.dIdDTO request) throws Exception{
        // 400 - 데이터 없음
        if(request.getDId() == null) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 유저 확인 404 포함
        User user = findByStudentId(studentId);

        Delivery delivery = findByDId(request.getDId());
        Optional<Delivery> optDelivery = deliveryRepository.findBydId(request.getDId());
        Optional<Comment_Delivery> optCommentDelivery = comment_deliveryRepository.findByDcId(request.getDId());
        List<Comment_Delivery> listCommentDelivery = comment_deliveryRepository.findByDelivery(delivery);

        // 404 - 글 존재하지 않음
        if(optDelivery.isEmpty()) throw new CustomException(ErrorCode.NOT_EXIST);

        // 403 - 권한 없음(작성인 != 삭제요청인)
        if(!optDelivery.get().getUser().equals(user)) throw new CustomException(ErrorCode.ACCESS_NO_AUTH);

        // 409 - 아직 진행 중인 신청이 있으면 임의로 삭제 안됨
        if(listCommentDelivery.stream().anyMatch(s -> !(s.getState().equals(ApplyState.CANCELED) || s.getState().equals(ApplyState.REJECTED))))
            throw new CustomException(ErrorCode.CONFLICT);

        try {
            Delivery existingDelivery = optDelivery.get();
            existingDelivery.setState(PostState.DELETED);
            deliveryRepository.save(existingDelivery);

            return true;
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // 배달모집글 마감 [writer]
    @Transactional
    public Boolean finish(Long studentId, DeliveryRequest.dIdDTO request) throws Exception{
        // 400 - 데이터 없음
        if(request.getDId() == null) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 유저 확인 404 포함
        User user = findByStudentId(studentId);
        Optional<Delivery> optDelivery = deliveryRepository.findBydId(request.getDId());

        // 404 - 글 존재하지 않음
        if(optDelivery.isEmpty()) throw new CustomException(ErrorCode.NOT_EXIST);

        // 403 - 권한 없음(작성인 != 마감요청인)
        if(!optDelivery.get().getUser().equals(user)) throw new CustomException(ErrorCode.ACCESS_NO_AUTH);

        // 409 - 이미 글이 마감된 상태 or 삭제된 상태
        if(optDelivery.get().getState().equals(PostState.FINISHED) || optDelivery.get().getState().equals(PostState.DELETED)) throw new CustomException(ErrorCode.CONFLICT);

        try {
            Delivery delivery = optDelivery.get();
            delivery.setState(PostState.FINISHED);
            deliveryRepository.save(delivery);
            waitToFinish(delivery); // 대기 신청글 => 마감처리

            return true;
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }


    }

    // 배달모집 신청 [applicant]
    @Transactional
    public Boolean apply(Long studentId, DeliveryRequest.ApplyDTO request) throws Exception {
        // 400 - 데이터 없음
        if(request.getDId() == null || request.getContents() == null) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 유저 확인 404 포함
        User user = findByStudentId(studentId);
        Delivery delivery = findByDId(request.getDId());
        List<Comment_Delivery> listCommentDelivery = comment_deliveryRepository.findByUserAndDelivery(user, delivery);

        // 404 - 글 state가 DELETED OR FINISHED
        if(delivery.getState().equals(PostState.DELETED) || delivery.getState().equals(PostState.FINISHED))
            throw new CustomException(ErrorCode.NOT_EXIST);

        // 403 - 권한 없음 => 자신의 글에 신청한 경우(작성인 == 신청인)
        if(delivery.getUser().equals(user)) throw new CustomException(ErrorCode.ACCESS_NO_AUTH);

        // 신청 전적이 있는 상태에서
        if(!listCommentDelivery.isEmpty()) {
            // 409 - 신청 => 대기 상태 OR 수락 상태가 1개라도 있으면
            if(listCommentDelivery.stream().anyMatch(s -> s.getState().equals(ApplyState.WAITING) || s.getState().equals(ApplyState.ACCEPTED)))
                throw new CustomException(ErrorCode.CONFLICT);
        }

        try {
            Comment_Delivery comment = Comment_Delivery.builder()
                    .user(user)
                    .delivery(delivery)
                    .contents(request.getContents())
                    .details(request.getDetails())
                    .state(request.getState())
                    .build();
            comment_deliveryRepository.save(comment);

            Long receiver = delivery.getUser().getStudentId(); // 모집자
            String title = "같이 배달 New 신청";
            String contents = user.getNickname() + "님께서 <" + delivery.getTitle() + "> 같이 배달을 신청했어요!";
            sendPush(receiver, delivery.getDId(), title, contents);
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return true;
    }

    // 배달모집 신청 취소 [applicant]
    public Boolean cancel(Long studentId, DeliveryRequest.dcIdDTO request) throws Exception {
        // 400 - 데이터 없음
        if(request.getDcId() == null) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 유저 확인 404 포함
        User user = findByStudentId(studentId);
        Optional<Comment_Delivery> optComment_Delivery = comment_deliveryRepository.findByDcId(request.getDcId());

        // 404 - 해당 신청 글 없음
        if(optComment_Delivery.isEmpty()) throw new CustomException(ErrorCode.NOT_EXIST);

        // 403 - 권한 없음(신청자 != 신청글 삭제 요청인)
        if(!optComment_Delivery.get().getUser().equals(user)) throw new CustomException(ErrorCode.ACCESS_NO_AUTH);

        // 409 - 취소 불가 => 대기 상태가 아닌 모든 경우
        if(!optComment_Delivery.get().getState().equals(ApplyState.WAITING))
            throw new CustomException(ErrorCode.CONFLICT);

        try {
            Comment_Delivery comment_delivery = optComment_Delivery.get();
            comment_delivery.setState(ApplyState.CANCELED);
            comment_deliveryRepository.save(comment_delivery);
            return true;
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // 배달모집 신청 수락 [writer]
    public Boolean accept(Long studentId, DeliveryRequest.dcIdDTO request) throws Exception {
        // 400 - 데이터 없음
        if(request.getDcId() == null) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        // 유저 확인 404 포함
        User user = findByStudentId(studentId);
        Optional<Comment_Delivery> optComment_Delivery = comment_deliveryRepository.findByDcId(request.getDcId());

        Optional<Delivery> optDelivery = deliveryRepository.findBydId(optComment_Delivery.get().getDelivery().getDId());
        // 404 - 배달글 존재하지 않음
        if(optDelivery.isEmpty()) throw new CustomException(ErrorCode.NOT_EXIST);

        // 403 - 신청 수락 권한 없음
        if(!optDelivery.get().getUser().equals(user)) throw new CustomException(ErrorCode.ACCESS_NO_AUTH);

        // 409 - 글이 활성화 상태가 아님 OR 신청이 대기 상태가 아님(수락, 삭제, 거절 등)
        if(!optDelivery.get().getState().equals(PostState.ACTIVE) || !optComment_Delivery.get().getState().equals(ApplyState.WAITING)) throw new CustomException(ErrorCode.CONFLICT);


        try {
            Comment_Delivery comment_delivery = optComment_Delivery.get();
            comment_delivery.setState(ApplyState.ACCEPTED);
            comment_deliveryRepository.save(comment_delivery);

            Long receiver = optComment_Delivery.get().getUser().getStudentId(); // 신청자
            String title = "같이 배달 신청 수락";
            String contents = user.getNickname() + "님께서 <" + optDelivery.get().getTitle() + "> 같이 배달을 수락했어요!";
            sendPush(receiver, optDelivery.get().getDId(), title, contents);

            return true;
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // 배달모집 신청 거부 [writer]
    public Boolean reject(Long studentId, DeliveryRequest.dcIdDTO request) throws Exception {
        // 400 - 데이터 없음
        if(request.getDcId() == null) throw new CustomException(ErrorCode.INSUFFICIENT_DATA);

        Optional<Comment_Delivery> optComment_Delivery = comment_deliveryRepository.findByDcId(request.getDcId());
        // 404 - 해당 신청 글 없음
        if(optComment_Delivery.isEmpty()) throw new CustomException(ErrorCode.NOT_EXIST);

        // 유저 확인 404 포함
        User user = findByStudentId(studentId);

        // 배달글 404 포함
        Delivery delivery = findByDId(optComment_Delivery.get().getDelivery().getDId());

        // 403 - 거절 수락 권한 없음
        if(!delivery.getUser().equals(user)) throw new CustomException(ErrorCode.ACCESS_NO_AUTH);

        // 409 - 글이 활성화 상태가 아님 OR 신청이 대기 상태가 아님(수락, 삭제, 거절 등)
        if(!delivery.getState().equals(PostState.ACTIVE) || !optComment_Delivery.get().getState().equals(ApplyState.WAITING)) throw new CustomException(ErrorCode.CONFLICT);

        try {
            Comment_Delivery comment_delivery = optComment_Delivery.get();
            comment_delivery.setState(ApplyState.REJECTED);
            comment_deliveryRepository.save(comment_delivery);

            Long receiver = optComment_Delivery.get().getUser().getStudentId(); // 신청자
            String title = "같이 배달 신청 거절";
            String contents = user.getNickname() + "님께서 <" + delivery.getTitle() + "> 같이 배달을 거절했어요!";
            sendPush(receiver, delivery.getDId(), title, contents);
            return true;
        }
        catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // 학번으로 유저의 정보를 가져오는 메서드
    public User findByStudentId(Long studentId) {
        return userRepository.findByStudentId(studentId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_EXIST));
    }

    // dId로 배달 모집 글 정보를 가져오는 메서드
    public Delivery findByDId(Long dId) {
        return deliveryRepository.findBydId(dId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_EXIST));
    }

    // dcId로 배달 신청 정보를 가져오는 메서드
    public Comment_Delivery findByDcId(Long dcId) {
        return comment_deliveryRepository.findByDcId(dcId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_EXIST));
    }

    public void waitToFinish(Delivery delivery) {
        // ### 마감할 때 대기 중인 신청글을 모두 FINISHED 처리 ###
        // 마감된 배달 모집 글에 따른 신청글 => state가 ApplyState.WAITING인 글의 state를 FINISHED로 변경
        List<Comment_Delivery> waitingComments = comment_deliveryRepository.findByDeliveryAndState(delivery, ApplyState.WAITING);
        waitingComments.forEach(comment -> comment.setState(ApplyState.FINISHED));
        comment_deliveryRepository.saveAll(waitingComments);
    }

    // 푸시 처리
    public void sendPush(Long studentId, Long id, String title, String contents) throws Exception {
        User user = userRepository.findByStudentId(studentId).orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_EXIST));
        String fcm = user.getFcm();

        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(contents)
                .build();

        Message message = Message.builder()
                .setToken(fcm)
                .setNotification(notification)
                .build();

        try {
            // 알림 보내기
            firebaseMessaging.send(message);

            // 알림 내역 저장
            Push push = Push.builder()
                    .user(user)
                    .type(EntityCode.DELIVERY)
                    .id(id)
                    .contents(contents)
                    .build();
            pushRepository.save(push);
        } catch (FirebaseMessagingException e) {
            e.printStackTrace();
        }
    }
}
