package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

import java.util.UUID

object CustomerBookService {

  private final class CustomerBookServiceImpl() extends smithy.CustomerBookService[ServiceTask] {

    /** HTTP GET /get/customer/{customerID} (X-Organization-ID header) */
    override def getCustomerGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.GetCustomerGetResponse] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("getCustomerGet is not implemented yet"))

    /** HTTP GET /get/customers (X-Organization-ID header) */
    override def getCustomersGet(organizationID: UUID): ServiceTask[smithy.GetCustomersGetResponse] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("getCustomersGet is not implemented yet"))

    /** HTTP POST /insert/customers (X-Organization-ID header) */
    override def insertCustomersPost(
        organizationID: UUID,
        request: smithy.InsertCustomersPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("insertCustomersPost is not implemented yet"))

    /** HTTP PUT /update/customers (X-Organization-ID header) */
    override def updateCustomersPut(
        organizationID: UUID,
        request: smithy.UpdateCustomersPutRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("updateCustomersPut is not implemented yet"))

    /** HTTP POST /delete/customers (X-Organization-ID header) */
    override def deleteCustomersPost(
        organizationID: UUID,
        request: smithy.DeleteCustomersPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("deleteCustomersPost is not implemented yet"))
  }

  private def observed(
      service: smithy.CustomerBookService[ServiceTask]
  ): smithy.CustomerBookService[Task] =
    new smithy.CustomerBookService[Task] {

      /** HTTP GET /get/customer/{customerID} (X-Organization-ID header) */
      override def getCustomerGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.GetCustomerGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomerGet(organizationID, customerID))

      /** HTTP GET /get/customers (X-Organization-ID header) */
      override def getCustomersGet(organizationID: UUID): Task[smithy.GetCustomersGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomersGet(organizationID))

      /** HTTP POST /insert/customers (X-Organization-ID header) */
      override def insertCustomersPost(
          organizationID: UUID,
          request: smithy.InsertCustomersPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.insertCustomersPost(organizationID, request))

      /** HTTP PUT /update/customers (X-Organization-ID header) */
      override def updateCustomersPut(
          organizationID: UUID,
          request: smithy.UpdateCustomersPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.updateCustomersPut(organizationID, request))

      /** HTTP POST /delete/customers (X-Organization-ID header) */
      override def deleteCustomersPost(
          organizationID: UUID,
          request: smithy.DeleteCustomersPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.deleteCustomersPost(organizationID, request))
    }

  val local = ZLayer.derive[CustomerBookServiceImpl].project[smithy.CustomerBookService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
